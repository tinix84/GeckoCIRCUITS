import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, LinearProgress, Stack } from '@mui/material';
import { api, requireData } from './api/client';
import { CircuitEditor } from './CircuitEditor';
import { Scope } from './Scope';
import { errorMessage } from './errors';
import { useSession } from './session';

async function loadTutorial(source: string, signal: AbortSignal) {
  if (!/^(?:\/|[a-z][a-z\d+.-]*:)/i.test(source)) {
    return api.POST('/api/v1/circuits/{circuitId}/clone', {
      params: { path: { circuitId: source } }, signal,
    });
  }

  const url = new URL(source, window.location.href);
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('Use an HTTP or HTTPS circuit URL.');
  }
  const response = await fetch(url, { signal, credentials: 'omit' });
  if (!response.ok) throw new Error('Could not download the tutorial circuit (HTTP ' + response.status + ').');
  const basename = url.pathname.split('/').at(-1) ?? '';
  const filename = basename.toLowerCase().endsWith('.ipes') ? basename : 'tutorial.ipes';
  const circuit = await response.blob();
  return api.POST('/api/v1/circuits/parse', {
    body: { file: filename },
    bodySerializer: () => {
      const form = new FormData();
      form.append('file', circuit, filename);
      return form;
    },
    signal,
  });
}

export function TutorialEmbed({ search, login }: { search: string; login: ReactNode }) {
  const { active } = useSession();
  const [options] = useState(() => {
    const query = new URLSearchParams(search);
    return {
      circuit: query.get('circuit')?.trim(),
      signals: (query.get('signals') ?? '').split(',').map((name) => name.trim()).filter(Boolean),
    };
  });
  const [needsLogin, setNeedsLogin] = useState(false);
  const [circuitId, setCircuitId] = useState<string>();
  const [simulationId, setSimulationId] = useState<string>();
  const [error, setError] = useState('');

  useEffect(() => {
    if (!options.circuit || (needsLogin && !active)) return;
    const controller = new AbortController();
    loadTutorial(options.circuit, controller.signal).then((response) => {
      if (controller.signal.aborted) return;
      if (response.response.status === 401 && !active) {
        setNeedsLogin(true);
        return;
      }
      const result = requireData(response, 'Could not load the tutorial circuit');
      if (!result.circuitId) throw new Error('The server did not return a circuit ID.');
      setCircuitId(result.circuitId);
    }).catch((cause) => {
      if (!controller.signal.aborted) setError(errorMessage(cause));
    });
    return () => controller.abort();
  }, [active, needsLogin, options.circuit]);

  if (!options.circuit) return <Alert severity="error">Specify a tutorial circuit.</Alert>;
  if (needsLogin && !active) return <>{login}</>;
  if (error) return <Alert severity="error">{error}</Alert>;
  if (!circuitId) return <LinearProgress aria-label="Loading tutorial circuit" />;
  return <Stack spacing={3}>
    <CircuitEditor circuitId={circuitId} onSimulation={setSimulationId} />
    {simulationId && <Scope simulationId={simulationId} initialSignals={options.signals} />}
  </Stack>;
}
