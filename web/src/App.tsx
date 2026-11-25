import { lazy, Suspense, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Alert, Box, Button, Container, LinearProgress, Paper, Stack, TextField, Typography } from '@mui/material';
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { api, requireData } from './api/client';
import type { components } from './api/schema';
import { useSession } from './session';

import { errorMessage } from './errors';
const Scope = lazy(() => import('./Scope').then(({ Scope }) => ({ default: Scope })));
const CircuitEditor = lazy(() => import('./CircuitEditor').then(({ CircuitEditor }) => ({ default: CircuitEditor })));

function Login() {
  const { signIn } = useSession();
  const [key, setKey] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await signIn(key.trim());
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return <Container className="content">
    <Paper variant="outlined" className="login" sx={{ p: 4 }}>
      <Stack component="form" onSubmit={submit} spacing={2.5}>
        <Typography variant="h1">Sign in</Typography>
        <TextField label="API key" type="password" autoComplete="off" required
          value={key} onChange={(event) => setKey(event.target.value)} />
        {error && <Alert severity="error">{error}</Alert>}
        <Button type="submit" variant="contained" disabled={busy || !key.trim()}>Sign in</Button>
      </Stack>
    </Paper>
  </Container>;
}

function Pagination({ current, canNext, onChange }: {
  current: number; canNext: boolean; onChange: (page: number) => void;
}) {
  return <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
    <Button disabled={current === 0} onClick={() => onChange(current - 1)}>Previous</Button>
    <Typography>Page {current + 1}</Typography>
    <Button disabled={!canNext} onClick={() => onChange(current + 1)}>Next</Button>
  </Stack>;
}

function Circuits() {
  const [page, setPage] = useState(0);
  const [list, setList] = useState<components['schemas']['CircuitListResponse']>();
  const [error, setError] = useState('');
  const [uploadError, setUploadError] = useState('');
  const [uploading, setUploading] = useState(false);
  const [revision, setRevision] = useState(0);
  const navigate = useNavigate();

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    setList(undefined);
    api.GET('/api/v1/circuits', {
      params: { query: { page, sort: 'createdAt,desc' } },
      signal: controller.signal,
    }).then((response) => requireData(response, 'Could not load circuits'))
      .then(setList)
      .catch((cause) => { if (!controller.signal.aborted) setError(errorMessage(cause)); });
    return () => controller.abort();
  }, [page, revision]);

  async function upload(file: File) {
    setUploading(true);
    setUploadError('');
    try {
      const result = await api.POST('/api/v1/circuits/parse', {
        body: { file: file.name },
        bodySerializer: () => {
          const form = new FormData();
          form.append('file', file);
          return form;
        },
      });
      const circuit = requireData(result, 'Could not upload the circuit');
      setPage(0);
      setRevision((value) => value + 1);
      if (circuit.circuitId) navigate('/circuits/' + encodeURIComponent(circuit.circuitId));
    } catch (cause) {
      setUploadError(errorMessage(cause));
    } finally {
      setUploading(false);
    }
  }

  return <Stack spacing={2.5}>
    <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 2 }}>
      <Typography variant="h1">Circuit library</Typography>
      <Button component="label" variant="contained" disabled={uploading}>
        {uploading ? 'Uploading…' : 'Upload circuit'}
        <input hidden type="file" accept=".ipes" aria-label="Upload circuit" disabled={uploading}
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) void upload(file);
          }} />
      </Button>
    </Stack>
    {uploadError && <Alert severity="error">{uploadError}</Alert>}
    {error && <Alert severity="error">{error}</Alert>}
    {!list && !error && <LinearProgress aria-label="Loading circuits" />}
    {list && <Paper variant="outlined" sx={{ p: 2.5 }}>
      <Stack spacing={1.5}>
        {!list.circuits?.length && <Typography color="text.secondary">No circuits loaded.</Typography>}
        {list.circuits?.map((circuit) => circuit.circuitId && <Link key={circuit.circuitId}
          to={'/circuits/' + encodeURIComponent(circuit.circuitId)} className="route-row">
          {circuit.filename ?? circuit.circuitId}
        </Link>)}
      </Stack>
    </Paper>}
    <Pagination current={page} canNext={Boolean(list && page + 1 < (list.page?.totalPages ?? 1))}
      onChange={setPage} />
  </Stack>;
}

function simulationRows(payload: Record<string, unknown>) {
  const rows = payload.simulations;
  return typeof rows === 'object' && rows ? Object.entries(rows).flatMap(([id, row]: [string, unknown]) => {
    if (typeof row !== 'object' || !row) return [];
    return [{ id, status: 'status' in row ? String(row.status ?? '') : '' }];
  }) : [];
}

function Dashboard() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Record<string, unknown>>();
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setData(undefined);
    setError('');
    api.GET('/api/v1/simulations', {
      params: { query: { page, sort: 'createdAt,desc' } },
      signal: controller.signal,
    }).then((response) => requireData(response, 'Could not load simulations'))
      .then(setData)
      .catch((cause) => { if (!controller.signal.aborted) setError(errorMessage(cause)); });
    return () => controller.abort();
  }, [page]);

  const rows = data ? simulationRows(data) : [];
  const metadata = data?.page;
  const totalPages = typeof metadata === 'object' && metadata && 'totalPages' in metadata
    ? Number(metadata.totalPages)
    : 1;

  return <Stack spacing={2.5}>
    <Typography variant="h1">Recent simulations</Typography>
    {error && <Alert severity="error">{error}</Alert>}
    {!data && !error && <LinearProgress aria-label="Loading simulations" />}
    {data && <Paper variant="outlined" sx={{ p: 2.5 }}>
      <Stack spacing={1.5}>
        {!rows.length && <Typography color="text.secondary">No simulations yet.</Typography>}
        {rows.map(({ id, status }) => <Stack key={id} direction="row" sx={{ justifyContent: "space-between", gap: 2 }}>
          <Link to={'/simulations/' + encodeURIComponent(id)} className="route-row">{id}</Link>
          <Typography color="text.secondary">{status}</Typography>
        </Stack>)}
      </Stack>
    </Paper>}
    <Pagination current={page} canNext={page + 1 < totalPages} onChange={setPage} />
  </Stack>;
}





export function App() {
  const { active, signOut } = useSession();
  const location = useLocation();
  return <>
    <Box component="header" className="topbar">
      <Container className="topbar-inner" sx={{ display: 'flex' }}>
        <Link className="brand" to="/">GeckoCIRCUITS</Link>
        {active && <>
          <nav aria-label="Main navigation">
            <NavLink to="/" end>Simulations</NavLink>
            <NavLink to="/circuits">Circuits</NavLink>
          </nav>
          <Button className="sign-out" color="inherit" onClick={signOut}>Sign out</Button>
        </>}
      </Container>
    </Box>
    {active ? <Container component="main" className="content">
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/circuits" element={<Circuits />} />
        <Route path="/circuits/:id" element={<Suspense fallback={<LinearProgress aria-label="Loading circuit editor" />}><CircuitEditor /></Suspense>} />
        <Route path="/simulations/:id" element={<Suspense fallback={<LinearProgress aria-label="Loading scope" />}><Scope /></Suspense>} />
        <Route path="*" element={<Navigate replace to="/" />} />
      </Routes>
    </Container> : <main key={location.pathname}><Login /></main>}
  </>;
}
