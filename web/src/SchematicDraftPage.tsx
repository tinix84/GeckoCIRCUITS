import { useRef, useState } from 'react';
import type { DragEvent, PointerEvent } from 'react';
import { Alert, Button, Chip, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { api, requireData } from './api/client';
import type { components } from './api/schema';
import { errorMessage } from './errors';
import { symbols } from './CircuitSchematic';

type Request = components['schemas']['SchematicDraftRequest'];
type Part = NonNullable<Request['parts']>[number];
type Wire = NonNullable<Request['wires']>[number];
type Terminal = NonNullable<Wire['from']>;
type Kind = NonNullable<Part['kind']>;
type Point = [number, number];

const palette: Record<Kind, { label: string; prefix: string; value: number; unit: string }> = {
  RESISTOR: { label: 'Resistor', prefix: 'R', value: 1000, unit: 'Resistance (Ω)' },
  INDUCTOR: { label: 'Inductor', prefix: 'L', value: 0.001, unit: 'Inductance (H)' },
  CAPACITOR: { label: 'Capacitor', prefix: 'C', value: 0.000001, unit: 'Capacitance (F)' },
  VOLTAGE_SOURCE: { label: 'Voltage source', prefix: 'V', value: 5, unit: 'Voltage (V)' },
  CURRENT_SOURCE: { label: 'Current source', prefix: 'I', value: 0.005, unit: 'Current (A)' },
  DIODE: { label: 'Diode', prefix: 'D', value: 0, unit: '' },
  SWITCH: { label: 'Switch', prefix: 'S', value: 1, unit: 'State (0 open, 1 closed)' },
  TRANSFORMER: { label: 'Transformer', prefix: 'T', value: 2, unit: 'Turns ratio' },
  GROUND: { label: 'Ground', prefix: 'GND', value: 0, unit: '' },
};

const portOffsets: Record<'ground' | 'twoPort' | 'transformer', Point[]> = {
  ground: [[0, 0]],
  twoPort: [[0, -2], [0, 2]],
  transformer: [[-1, 2], [-1, -2], [1, 2], [1, -2]],
};

function offsets(part: Part) {
  return portOffsets[part.kind === 'GROUND' ? 'ground' : part.kind === 'TRANSFORMER' ? 'transformer' : 'twoPort'];
}

function portPoint(part: Part, port: number): Point {
  const [dx, dy] = offsets(part)[port];
  return [(part.x ?? 0) + dx, (part.y ?? 0) + dy];
}

function sameTerminal(first: Terminal | undefined, second: Terminal | undefined) {
  return Boolean(first && second && first.part === second.part && first.port === second.port);
}

const symbolType: Record<Kind, number> = {
  RESISTOR: 1, INDUCTOR: 2, CAPACITOR: 3, VOLTAGE_SOURCE: 4, CURRENT_SOURCE: 5,
  DIODE: 6, SWITCH: 7, TRANSFORMER: 23, GROUND: 0,
};
const maxX = 44;
const maxY = 28;

export function SchematicDraftPage() {
  const [parts, setParts] = useState<Part[]>([]);
  const [wires, setWires] = useState<Wire[]>([]);
  const [kind, setKind] = useState<Kind>();
  const [selected, setSelected] = useState('');
  const [terminal, setTerminal] = useState<Terminal>();
  const [filename, setFilename] = useState('schematic.ipes');
  const [duration, setDuration] = useState('0.005');
  const [step, setStep] = useState('0.000001');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const board = useRef<SVGSVGElement>(null);
  const moving = useRef<{ name: string; start: Point; position: Point }>();
  const partIndex = useRef(0);
  const navigate = useNavigate();

  const activePart = parts.find(({ name }) => name === selected);
  const byName = new Map(parts.map((part) => [part.name, part]));

  function coordinates(clientX: number, clientY: number): Point {
    const matrix = board.current?.getScreenCTM();
    if (!matrix) return [2, 3];
    const { x, y } = new DOMPoint(clientX, clientY).matrixTransform(matrix.inverse());
    return [Math.round(x), Math.round(y)];
  }

  function stayInGrid([x, y]: Point): Point {
    return [Math.max(2, Math.min(maxX - 2, x)), Math.max(3, Math.min(maxY - 3, y))];
  }

  function place(partKind: Kind, point: Point) {
    if (parts.length >= 64) return;
    const [x, y] = stayInGrid(point);
    const item = palette[partKind];
    const name = item.prefix + (++partIndex.current);
    setParts((current) => [...current, { kind: partKind, name, x, y, value: item.value }]);
    setSelected(name);
    setKind(undefined);
    setTerminal(undefined);
  }

  function drop(event: DragEvent<SVGSVGElement>) {
    event.preventDefault();
    if (saving) return;
    const value = event.dataTransfer.getData('application/x-gecko-part');
    if (Object.hasOwn(palette, value)) place(value as Kind, coordinates(event.clientX, event.clientY));
  }

  function connect(next: Terminal) {
    if (saving) return;
    setKind(undefined);
    if (!terminal) {
      setTerminal(next);
    } else if (sameTerminal(terminal, next)) {
      setTerminal(undefined);
    } else {
      const duplicate = wires.some(({ from, to }) =>
        (sameTerminal(from, terminal) && sameTerminal(to, next)) ||
        (sameTerminal(from, next) && sameTerminal(to, terminal)),
      );
      if (!duplicate && wires.length < 256) setWires((current) => [...current, { from: terminal, to: next }]);
      setTerminal(undefined);
    }
  }

  function movePart(event: PointerEvent<SVGSVGElement>) {
    if (!moving.current || saving) return;
    const { start, position, name } = moving.current;
    const [x, y] = coordinates(event.clientX, event.clientY);
    const [nextX, nextY] = stayInGrid([position[0] + x - start[0], position[1] + y - start[1]]);
    setParts((current) => current.map((part) => part.name === name ? { ...part, x: nextX, y: nextY } : part));
  }

  function removeSelected() {
    setParts((current) => current.filter(({ name }) => name !== selected));
    setWires((current) => current.filter(({ from, to }) => from?.part !== selected && to?.part !== selected));
    if (terminal?.part === selected) setTerminal(undefined);
    setSelected('');
  }

  async function save() {
    setSaving(true);
    setError('');
    try {
      const result = await api.POST('/api/v1/circuits/schematic', {
        body: { filename: filename.trim(), parts, wires, duration: Number(duration), step: Number(step) },
      });
      const circuit = requireData(result, 'Could not save the schematic');
      if (!circuit.circuitId) throw new Error('The server did not return a circuit ID.');
      navigate('/circuits/' + encodeURIComponent(circuit.circuitId));
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setSaving(false);
    }
  }

  const ready = /^[A-Za-z0-9][A-Za-z0-9_. -]*\.ipes$/.test(filename.trim()) &&
    parts.some(({ kind }) => kind === 'GROUND') && parts.some(({ kind }) => kind !== 'GROUND') &&
    parts.every(({ value }) => typeof value === 'number' && Number.isFinite(value)) &&
    Number(step) > 0 && Number(duration) >= Number(step);

  return <Stack spacing={2.5}>
    <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 2 }}>
      <Typography variant="h1">New schematic</Typography>
      <Button variant="contained" disabled={!ready || saving} onClick={() => void save()}>Save circuit</Button>
    </Stack>
    {error && <Alert severity="error">{error}</Alert>}
    <div className="editor-layout">
      <Paper variant="outlined" sx={{ p: 1.5 }}>
        <Stack spacing={1.5}>
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.5 }} aria-label="Component palette">
            {(Object.entries(palette) as [Kind, (typeof palette)[Kind]][]).map(([value, item]) =>
              <Button key={value} draggable={!saving} disabled={saving} size="small"
                variant={kind === value ? 'contained' : 'outlined'} aria-pressed={kind === value}
                onClick={() => { setKind(value); setTerminal(undefined); }}
                onDragStart={(event) => event.dataTransfer.setData('application/x-gecko-part', value)}>
                {item.label}
              </Button>,
            )}
          </Stack>
          <Typography color="text.secondary">
            {kind ? 'Click the grid to place a ' + palette[kind].label.toLowerCase() + '.'
              : terminal ? 'Select the other terminal.'
              : 'Drag a component to the grid or select it and click the grid. Select two terminals to connect them.'}
          </Typography>
          <svg ref={board} className="circuit-schematic" viewBox={'0 0 ' + maxX + ' ' + maxY}
            aria-label="Schematic drawing grid" onDragOver={(event) => event.preventDefault()} onDrop={drop}
            onPointerMove={movePart} onPointerUp={() => { moving.current = undefined; }}
            onPointerCancel={() => { moving.current = undefined; }}
            onClick={(event) => {
              if (kind && !saving) place(kind, coordinates(event.clientX, event.clientY));
              else setTerminal(undefined);
            }}>
            <defs><pattern id="draw-grid" width="1" height="1" patternUnits="userSpaceOnUse">
              <circle cx="0" cy="0" r=".055" fill="#a5bbb0" />
            </pattern></defs>
            <rect width={maxX} height={maxY} fill="url(#draw-grid)" />
            {wires.map(({ from, to }, index) => {
              const first = byName.get(from?.part);
              const second = byName.get(to?.part);
              if (!first || !second || from?.port === undefined || to?.port === undefined) return null;
              const [fromX, fromY] = portPoint(first, from.port);
              const [toX, toY] = portPoint(second, to.port);
              return <polyline key={index} data-testid="draft-wire" points={fromX + ',' + fromY
                + ' ' + toX + ',' + fromY + ' ' + toX + ',' + toY} fill="none" stroke="#38685b" strokeWidth=".14" />;
            })}
            {parts.map((part) => <g key={part.name} transform={'translate(' + part.x + ',' + part.y + ')'}>
              <g role="button" tabIndex={0} aria-label={part.name}
                onPointerDown={(event) => {
                  event.stopPropagation();
                  if (saving || event.button !== 0 || !part.name) return;
                  setSelected(part.name);
                  moving.current = { name: part.name, start: coordinates(event.clientX, event.clientY),
                    position: [part.x ?? 0, part.y ?? 0] };
                  board.current?.setPointerCapture(event.pointerId);
                }}
                onClick={(event) => { event.stopPropagation(); setSelected(part.name ?? ''); }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') setSelected(part.name ?? '');
                }}>
                {part.kind === 'GROUND'
                  ? <path d="M0 0v.35M-.8 .35h1.6M-.55 .58h1.1M-.27 .8h.54" fill="none" stroke="#315e51" strokeWidth=".13" />
                  : <>
                    <rect x="-1.15" y="-1.5" width="2.3" height="3" rx=".15"
                      fill={selected === part.name ? '#c7eade' : 'transparent'} />
                    <path d={symbols[symbolType[part.kind!]]} fill="none" stroke="#315e51" strokeWidth=".15" />
                  </>}
                <text x="1.2" y="-0.15" fontSize=".74" fill="#18372d">{part.name}</text>
              </g>
              {offsets(part).map(([x, y], port) => {
                const label = part.name + ' terminal ' + (port + 1);
                const endpoint = { part: part.name, port };
                return <circle key={port} role="button" tabIndex={0} aria-label={label} cx={x} cy={y} r=".27"
                  fill={sameTerminal(terminal, endpoint) ? '#e6a731' : '#116657'}
                  onClick={(event) => { event.stopPropagation(); connect(endpoint); }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      connect(endpoint);
                    }
                  }} />;
              })}
            </g>)}
          </svg>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <Chip label={parts.length + ' parts'} variant="outlined" />
            <Button size="small" disabled={!wires.length || saving}
              onClick={() => setWires((current) => current.slice(0, -1))}>Undo wire</Button>
            <Button size="small" color="error" disabled={!selected || saving} onClick={removeSelected}>Remove part</Button>
          </Stack>
        </Stack>
      </Paper>
      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Stack spacing={2}>
          <TextField label="Filename" size="small" value={filename} disabled={saving}
            onChange={(event) => setFilename(event.target.value)} />
          <TextField label="Duration (s)" size="small" type="number" value={duration} disabled={saving}
            slotProps={{ htmlInput: { step: 'any' } }} onChange={(event) => setDuration(event.target.value)} />
          <TextField label="Time step (s)" size="small" type="number" value={step} disabled={saving}
            slotProps={{ htmlInput: { step: 'any' } }} onChange={(event) => setStep(event.target.value)} />
          {activePart?.kind && <TextField select label="Selected part" size="small" value={selected}
            disabled={saving} onChange={(event) => setSelected(event.target.value)}>
            {parts.map(({ name }) => <MenuItem key={name} value={name}>{name}</MenuItem>)}
          </TextField>}
          {activePart?.kind && palette[activePart.kind].unit && <TextField label={palette[activePart.kind].unit}
            size="small" type="number" disabled={saving} value={activePart.value ?? ''}
            slotProps={{ htmlInput: { step: 'any' } }}
            onChange={(event) => {
              const text = event.target.value;
              setParts((current) => current.map((part) => part.name === selected
                ? { ...part, value: text === '' ? undefined : Number(text) } : part));
            }} />}
        </Stack>
      </Paper>
    </div>
  </Stack>;
}
