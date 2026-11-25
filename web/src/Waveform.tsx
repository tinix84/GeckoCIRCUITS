import { useEffect, useRef } from 'react';
import { Button, Stack } from '@mui/material';
import uPlot from 'uplot';
import type { AlignedData } from 'uplot';
import { closestSample, reducedRows } from './scopeData';
import type { CursorPair, Trace } from './scopeData';
import 'uplot/dist/uPlot.min.css';

const colors = ['#007663', '#335ab0', '#b35217', '#a34282', '#6a7224'];

export function Waveform({ time, traces, cursors, onCursor, frequency = false, chartRef }: {
  time: number[];
  traces: Trace[];
  cursors?: CursorPair;
  onCursor?: (time: number) => void;
  frequency?: boolean;
  chartRef?: (chart: uPlot | undefined) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const plot = useRef<uPlot>();
  const cursorValues = useRef(cursors);
  const selectCursor = useRef(onCursor);
  const reportChart = useRef(chartRef);
  cursorValues.current = cursors;
  selectCursor.current = onCursor;
  reportChart.current = chartRef;

  useEffect(() => {
    const mount = host.current;
    if (!mount || time.length < 2 || !traces.length) return;
    const cache = new Map<number, AlignedData>();
    const bucketFor = (width: number, samples: number) =>
      2 ** Math.max(0, Math.floor(Math.log2(samples / Math.max(240, width))));
    const dataFor = (bucket: number) => {
      const saved = cache.get(bucket);
      if (saved) return saved;
      const data = reducedRows(time, traces, bucket);
      cache.set(bucket, data);
      return data;
    };
    let bucket = bucketFor(mount.clientWidth, time.length);
    function setResolution(instance: uPlot) {
      const min = instance.scales.x.min ?? time[0];
      const max = instance.scales.x.max ?? time.at(-1)!;
      const size = closestSample(time, max) - closestSample(time, min) + 1;
      const next = bucketFor(instance.width, size);
      if (next === bucket) return;
      bucket = next;
      instance.setData(dataFor(bucket), false);
      instance.redraw();
    }
    const chart = new uPlot({
      width: Math.max(240, mount.clientWidth),
      height: 360,
      scales: { x: { time: false } },
      axes: [{ label: frequency ? 'Frequency (Hz)' : 'Time (s)' }, { label: frequency ? 'Amplitude' : 'Value' }],
      cursor: { drag: { x: true, y: false } },
      series: [
        { label: frequency ? 'Frequency' : 'Time' },
        ...traces.map(({ name }, index) => ({ label: name, stroke: colors[index % colors.length], width: 1.4 })),
      ],
      hooks: {
        setScale: [(instance, key) => { if (key === 'x') setResolution(instance); }],
        draw: [(instance) => {
          if (!cursorValues.current) return;
          const { ctx, bbox, scales } = instance;
          ctx.save();
          ctx.strokeStyle = '#495b67';
          ctx.setLineDash([5, 4]);
          for (const value of cursorValues.current) {
            if (value < (scales.x.min ?? 0) || value > (scales.x.max ?? Infinity)) continue;
            const x = instance.valToPos(value, 'x', true);
            ctx.beginPath();
            ctx.moveTo(x, bbox.top);
            ctx.lineTo(x, bbox.top + bbox.height);
            ctx.stroke();
          }
          ctx.restore();
        }],
      },
    }, dataFor(bucket), mount);
    plot.current = chart;
    reportChart.current?.(chart);

    let down = 0;
    const pointerDown = (event: PointerEvent) => { down = event.clientX; };
    const pointerUp = (event: PointerEvent) => {
      if (event.button !== 0 || Math.abs(event.clientX - down) > 4) return;
      const x = event.clientX - chart.over.getBoundingClientRect().left;
      selectCursor.current?.(time[closestSample(time, chart.posToVal(x, 'x'))]);
    };
    chart.over.addEventListener('pointerdown', pointerDown);
    chart.over.addEventListener('pointerup', pointerUp);

    const observer = new ResizeObserver(() => {
      const width = Math.max(240, mount.clientWidth);
      if (chart.width !== width) {
        chart.setSize({ width, height: 360 });
        setResolution(chart);
      }
    });
    observer.observe(mount);

    return () => {
      observer.disconnect();
      chart.over.removeEventListener('pointerdown', pointerDown);
      chart.over.removeEventListener('pointerup', pointerUp);
      chart.destroy();
      reportChart.current?.(undefined);
      plot.current = undefined;
    };
  }, [time, traces, frequency]);

  useEffect(() => { plot.current?.redraw(false, false); }, [cursors]);

  function move(direction: number) {
    const chart = plot.current;
    const lower = time[0];
    const upper = time[time.length - 1];
    if (!chart || lower === undefined || upper === undefined) return;
    const { min = lower, max = upper } = chart.scales.x;
    if (direction === 0) {
      chart.setScale('x', { min: lower, max: upper });
      return;
    }
    const span = max - min;
    const next = Math.max(lower, Math.min(upper - span, min + direction * span * 0.2));
    chart.setScale('x', { min: next, max: next + span });
  }

  return <>
    <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
      <Button onClick={() => move(-1)}>Pan left</Button>
      <Button onClick={() => move(1)}>Pan right</Button>
      <Button onClick={() => move(0)}>Reset zoom</Button>
    </Stack>
    <div className="chart-host" ref={host} role="img" aria-label={
      (frequency ? 'Fourier spectrum: ' : 'Waveforms: ') + traces.map(({ name }) => name).join(', ')
    } />
  </>;
}
