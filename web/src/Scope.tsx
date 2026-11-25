import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Checkbox, FormControlLabel, LinearProgress, MenuItem, Paper, Stack, Tab, Tabs, TextField, Typography,
} from '@mui/material';
import { useParams } from 'react-router-dom';
import type uPlot from 'uplot';
import { api, requireData } from './api/client';
import type { components } from './api/schema';
import { errorMessage } from './errors';
import { chartCsv, closestSample, numericSeries } from './scopeData';
import type { CursorPair, Trace } from './scopeData';
import { Waveform } from './Waveform';

type FourierResponse = components['schemas']['FourierResponse'];

import { downloadBlob } from './download';

export function Scope() {
  const { id } = useParams();
  if (!id) return <Alert severity="error">Select a simulation.</Alert>;
  return <SimulationScope key={id} id={id} />;
}

function SimulationScope({ id }: { id: string }) {
  const [status, setStatus] = useState('Loading');
  const [error, setError] = useState('');
  const [time, setTime] = useState<number[]>([]);
  const [traces, setTraces] = useState<Trace[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [tab, setTab] = useState<'waveform' | 'fft'>('waveform');
  const [cursors, setCursors] = useState<CursorPair>([0, 0]);
  const [nextCursor, setNextCursor] = useState(0);
  const [fftSignal, setFftSignal] = useState('');
  const [fftBusy, setFftBusy] = useState(false);
  const [fftError, setFftError] = useState('');
  const [fft, setFft] = useState<FourierResponse>();
  const chart = useRef<uPlot>();

  useEffect(() => {
    const controller = new AbortController();
    let timer: number | undefined;
    const path = { simulationId: id };
    async function poll() {
      try {
        const response = requireData(await api.GET('/api/v1/simulations/{simulationId}', {
          params: { path }, signal: controller.signal,
        }), 'Could not get simulation status');
        if (controller.signal.aborted) return;
        setStatus(response.status ?? 'Unknown');
        if (response.status === 'PENDING' || response.status === 'RUNNING') {
          timer = window.setTimeout(poll, 800);
          return;
        }
        if (response.status !== 'COMPLETED') {
          setError(response.errorMessage ?? 'The simulation did not complete.');
          return;
        }
        const names = Object.keys(response.results ?? {});
        const timeName = names.find((name) => name.toLowerCase() === 'time');
        if (!timeName) throw new Error('The simulation did not record time samples.');

        const loaded: Trace[] = [];
        for (const name of names) {
          const data = requireData(await api.GET('/api/v1/simulations/{simulationId}/results/{signalName}', {
            params: { path: { ...path, signalName: name } }, signal: controller.signal,
          }), 'Could not get ' + name);
          loaded.push({ name, values: numericSeries(data.data) });
        }
        if (controller.signal.aborted) return;
        const times = loaded.find(({ name }) => name === timeName)!.values;
        const signals = loaded.filter(({ name, values }) => name !== timeName && values.length === times.length);
        if (times.length < 2 || !signals.length) throw new Error('The simulation has no plottable signals.');
        const output = signals.find(({ name }) => /v_?out/i.test(name)) ?? signals[0];
        setTime(times);
        setTraces(signals);
        setSelected([output.name]);
        setFftSignal(output.name);
        setCursors([times[0], times[Math.min(times.length - 1, Math.floor(times.length * 0.1))]]);
      } catch (cause) {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      }
    }
    void poll();
    return () => { controller.abort(); window.clearTimeout(timer); };
  }, [id]);

  const visible = useMemo(() => traces.filter(({ name }) => selected.includes(name)), [traces, selected]);
  const fftData = useMemo(() => {
    const all = fft?.cnAmplitudes ?? [];
    const step = fft?.baseFrequency ?? 0;
    const values = all.slice(1);
    return {
      frequencies: values.map((_, index) => (index + 1) * step),
      traces: [{ name: fft?.signalName ?? 'Amplitude', values }],
      peaks: values.flatMap((amplitude, index) => {
        const lower = values[index - 1] ?? -Infinity;
        const upper = values[index + 1] ?? -Infinity;
        return amplitude > lower && amplitude >= upper
          ? [{ frequency: (index + 1) * step, amplitude }]
          : [];
      }).sort((first, second) => second.amplitude - first.amplitude).slice(0, 5),
      step,
    };
  }, [fft]);
  const activeTrace = visible.find(({ name }) => name === selected.at(-1));
  const deltaTime = cursors[1] - cursors[0];
  const deltaValue = activeTrace && time.length
    ? activeTrace.values[closestSample(time, cursors[1])] - activeTrace.values[closestSample(time, cursors[0])]
    : undefined;

  async function computeFourier() {
    if (time.length < 10 || fftBusy) return;
    const start = Math.min(Math.floor(time.length * 0.75), time.length - 10);
    const resolution = 2 ** Math.floor(Math.log2(time.length - start - 2));
    const harmonics = Math.min(100, resolution / 2 - 1);
    setFftBusy(true);
    setFftError('');
    setFft(undefined);
    try {
      const result = requireData(await api.POST('/api/v1/analysis/fourier', {
        params: { query: { harmonics } },
        body: {
          simulationId: id, signalName: fftSignal,
          startTime: time[start], endTime: time[time.length - 1],
        },
      }), 'Could not compute the Fourier spectrum');
      setFft(result);
    } catch (cause) {
      setFftError(errorMessage(cause));
    } finally {
      setFftBusy(false);
    }
  }

  function putCursor(index: number, value: number) {
    const snapped = time[closestSample(time, value)];
    setCursors((previous) => index === 0 ? [snapped, previous[1]] : [previous[0], snapped]);
  }

  return <Stack spacing={2.5}>
    <Typography variant="h1">Simulation results</Typography>
    <Typography color="text.secondary">{status}</Typography>
    {error && <Alert severity="error">{error}</Alert>}
    {!error && !time.length && <LinearProgress aria-label="Loading waveforms" />}
    {time.length > 0 && <>
      <Tabs value={tab} onChange={(_, value) => setTab(value)} aria-label="Result display">
        <Tab value="waveform" label="Waveforms" />
        <Tab value="fft" label="FFT" />
      </Tabs>
      <Paper variant="outlined" sx={{ p: { xs: 1.5, sm: 2.5 }, minWidth: 0 }}>
        {tab === 'waveform' ? <Stack spacing={1.5}>
          <Stack direction="row" sx={{ flexWrap: 'wrap' }}>
            {traces.map(({ name }) => <FormControlLabel key={name} label={name}
              control={<Checkbox checked={selected.includes(name)} onChange={(_, checked) =>
                setSelected((previous) => checked ? [...previous, name] : previous.filter((value) => value !== name))
              } />} />)}
          </Stack>
          {visible.length ? <Waveform time={time} traces={visible} cursors={cursors}
            onCursor={(value) => { putCursor(nextCursor, value); setNextCursor(1 - nextCursor); }}
            chartRef={(instance) => { chart.current = instance; }} />
            : <Typography color="text.secondary">Select a signal to display it.</Typography>}
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'center' }}>
            <Typography aria-label="Cursor delta time">Δt {deltaTime.toPrecision(6)} s</Typography>
            {activeTrace && deltaValue !== undefined && <Typography aria-label="Cursor delta value">
              {/^[uv]/i.test(activeTrace.name) ? 'ΔV' : /^i/i.test(activeTrace.name) ? 'ΔI' : 'Δ'} ({activeTrace.name}) {deltaValue.toPrecision(6)}
            </Typography>}
            <Button disabled={!visible.length}
              onClick={() => downloadBlob(new Blob([chartCsv(time, visible)], { type: 'text/csv' }), 'waveforms.csv')}>
              Export CSV
            </Button>
            <Button disabled={!visible.length} onClick={() => chart.current?.ctx.canvas.toBlob((blob) => {
              if (blob) downloadBlob(blob, 'waveforms.png');
            })}>Export PNG</Button>
          </Stack>
        </Stack> : <Stack spacing={2}>
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2 }}>
            <TextField select label="Fourier signal" value={fftSignal} disabled={fftBusy}
              onChange={(event) => { setFftSignal(event.target.value); setFft(undefined); }}>
              {traces.map(({ name }) => <MenuItem key={name} value={name}>{name}</MenuItem>)}
            </TextField>
            <Button variant="contained" onClick={() => void computeFourier()} disabled={fftBusy || time.length < 10}>
              {fftBusy ? 'Calculating…' : 'Calculate spectrum'}
            </Button>
          </Stack>
          {fftError && <Alert severity="error">{fftError}</Alert>}
          {fftData.frequencies.length > 0 && <>
            <Typography aria-label="Fourier bin width">Bin width {fftData.step.toPrecision(7)} Hz</Typography>
            <Waveform time={fftData.frequencies} traces={fftData.traces} frequency />
            {fftData.peaks.length > 0 && <Stack spacing={0.5} component="section" aria-label="Fourier peaks">
              <Typography component="h2" sx={{ fontWeight: 600 }}>Peak frequencies</Typography>
              {fftData.peaks.map(({ frequency, amplitude }) => (
                <Typography key={frequency} data-testid="fourier-peak">
                  {frequency.toPrecision(7)} Hz · {amplitude.toPrecision(5)}
                </Typography>
              ))}
            </Stack>}
          </>}
        </Stack>}
      </Paper>
    </>}
  </Stack>;
}
