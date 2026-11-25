import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Alert, Button, LinearProgress, Paper, Stack, TextField, Typography } from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { api, requireData } from './api/client';
import type { components } from './api/schema';
import { errorMessage } from './errors';
import { CircuitSchematic } from './CircuitSchematic';

type Model = components['schemas']['ComponentListResponse'];
type Component = components['schemas']['ComponentInfo'];

export function CircuitEditor() {
  const { id } = useParams();
  if (!id) return <Alert severity="error">Select a circuit.</Alert>;
  return <LoadedCircuit key={id} id={id} />;
}

function LoadedCircuit({ id }: { id: string }) {
  const [model, setModel] = useState<Model>();
  const [name, setName] = useState('');
  const [selection, setSelection] = useState<number>();
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  async function readComponents(signal?: AbortSignal) {
    return requireData(await api.GET('/api/v1/circuits/{circuitId}/components', {
      params: { path: { circuitId: id } }, signal,
    }), 'Could not read the circuit components');
  }

  useEffect(() => {
    const controller = new AbortController();
    const signal = controller.signal;
    Promise.all([
      readComponents(signal),
      api.GET('/api/v1/circuits/{circuitId}/info', {
        params: { path: { circuitId: id } }, signal,
      }).then((result) => requireData(result, 'Could not read the circuit information')),
    ]).then(([schematic, info]) => {
      if (!signal.aborted) {
        setModel(schematic);
        setName(info.filename ?? '');
      }
    }).catch((cause) => { if (!signal.aborted) setError(errorMessage(cause)); });
    return () => controller.abort();
  }, [id]);

  async function save(values: Record<string, number>) {
    setBusy(true);
    setActionError('');
    try {
      requireData(await api.PUT('/api/v1/circuits/{circuitId}/parameters', {
        params: { path: { circuitId: id } },
        body: { componentParameters: values },
      }), 'Could not save the component parameters');
      setModel(await readComponents());
      return true;
    } catch (cause) {
      setActionError(errorMessage(cause));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function run() {
    setBusy(true);
    setActionError('');
    try {
      const response = requireData(await api.POST('/api/v1/simulations', {
        body: { circuitId: id },
      }), 'Could not run the circuit');
      if (!response.simulationId) throw new Error('The server did not return a simulation ID.');
      navigate('/simulations/' + encodeURIComponent(response.simulationId));
    } catch (cause) {
      setActionError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  const selected = selection === undefined ? undefined : model?.components?.[selection];

  return <Stack spacing={2.5}>
    <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap' }}>
      <Stack>
        <Typography variant="h1">Circuit editor</Typography>
        {name && <Typography color="text.secondary">{name}</Typography>}
      </Stack>
      <Button variant="contained" disabled={!model || busy} onClick={() => void run()}>Run simulation</Button>
    </Stack>
    {error && <Alert severity="error">{error}</Alert>}
    {actionError && <Alert severity="error">{actionError}</Alert>}
    {!model && !error && <LinearProgress aria-label="Loading circuit" />}
    {model && <div className="editor-layout">
      <Paper variant="outlined" sx={{ p: 1.5, minWidth: 0 }}>
        <CircuitSchematic components={model.components ?? []} connections={model.connections ?? []}
          selected={selection} onSelect={(index) => { setSelection(index); setActionError(''); }} />
      </Paper>
      <Paper variant="outlined" sx={{ p: 2.5 }}>
        {selected
          ? <ParameterPanel key={selection} component={selected} saving={busy} onSave={save} />
          : <Typography color="text.secondary">Select a component to view its parameters.</Typography>}
      </Paper>
    </div>}
  </Stack>;
}

function ParameterPanel({ component, saving, onSave }: {
  component: Component;
  saving: boolean;
  onSave: (values: Record<string, number>) => Promise<boolean>;
}) {
  const [changes, setChanges] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);
  const fields = (component.configurationFields ?? []).flatMap((field) => {
    const label = field.name ?? field.indexedName;
    if (!label) return [];
    const value: unknown = component.parameters?.[label] ?? component.parameters?.[field.indexedName ?? ''];
    return typeof value === 'number' ? [{ label, value }] : [];
  });

  async function submit(event: FormEvent) {
    event.preventDefault();
    const values = Object.fromEntries(Object.entries(changes).map(([field, value]) =>
      [component.name + '.' + field, Number(value)],
    ));
    if (await onSave(values)) {
      setChanges({});
      setSaved(true);
    }
  }

  return <Stack component="form" spacing={2} onSubmit={submit}>
    <Typography variant="h2" sx={{ fontSize: '1.2rem', fontWeight: 700 }}>{component.name}</Typography>
    {fields.map(({ label, value }) => <TextField key={label} label={label} type="number"
      slotProps={{ htmlInput: { step: 'any' } }}
      value={changes[label] ?? String(value)} disabled={saving}
      onChange={(event) => {
        setSaved(false);
        setChanges((previous) => ({ ...previous, [label]: event.target.value }));
      }} />)}
    {fields.length > 0
      ? <Button variant="contained" type="submit"
          disabled={saving || !Object.keys(changes).length ||
            Object.values(changes).some((value) => !value.trim() || !Number.isFinite(Number(value)))}>
          Save parameters
        </Button>
      : <Typography color="text.secondary">This component has no editable numeric parameters.</Typography>}
    {saved && <Typography role="status">Parameters saved.</Typography>}
  </Stack>;
}
