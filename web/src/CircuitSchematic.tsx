import { useId, useMemo, useRef, useState } from 'react';
import type { PointerEvent } from 'react';
import { Button, Stack } from '@mui/material';
import type { components } from './api/schema';

type Component = components['schemas']['ComponentInfo'];
type Connection = components['schemas']['ConnectionInfo'];
type View = { x: number; y: number; width: number; height: number };

function extents(components: Component[], connections: Connection[]): View {
  const points = components.flatMap(({ position }) => position ? [position] : []);
  for (const wire of connections) points.push(...(wire.points ?? []));
  if (!points.length) return { x: 0, y: 0, width: 24, height: 18 };
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const [x, y] of points) {
    if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
    minX = Math.min(minX, x);
    maxX = Math.max(maxX, x);
    minY = Math.min(minY, y);
    maxY = Math.max(maxY, y);
  }
  if (!Number.isFinite(minX)) return { x: 0, y: 0, width: 24, height: 18 };
  return { x: minX - 4, y: minY - 4, width: maxX - minX + 8, height: maxY - minY + 8 };
}

function rotation(orientation = 503) {
  return ({ 503: 0, 502: 90, 501: 180, 500: 270 } as Record<number, number>)[orientation] ?? orientation;
}

export const symbols: Record<number, string> = {
  1: 'M0 -2v1M-.45 -1h.9v2h-.9zM0 1v1',
  2: 'M0 -2v.75q-.9 .25 0 .5q-.9 .25 0 .5q-.9 .25 0 .5q-.9 .25 0 .5V2',
  3: 'M0 -2v1.75M-.65 -.25H.65M-.65 .25H.65M0 .25V2',
  4: 'M0 -2v.95M0 1.05V2M1.05 0a1.05 1.05 0 1 1 -2.1 0a1.05 1.05 0 1 1 2.1 0M-.33 -.4h.66M0 -.67v.54M-.33 .42h.66',
  5: 'M0 -2v.95M0 1.05V2M1.05 0a1.05 1.05 0 1 1 -2.1 0a1.05 1.05 0 1 1 2.1 0M0 .55v-1.1m-.25 .3l.25 -.3l.25 .3',
  6: 'M0 -2v1.5M-.65 -.5h1.3L0 .5zM-.65 .5h1.3M0 .5V2',
  7: 'M0 -2v.85M0 1.15V2M0 1.15l.7 -1.9',
  23: 'M-1 -2v.8q1 .2 0 .6q1 .2 0 .6q1 .2 0 .6q1 .2 0 .6V2 M1 -2v.8q-1 .2 0 .6q-1 .2 0 .6q-1 .2 0 .6q-1 .2 0 .6V2',
};

export function CircuitSchematic({ components, connections, selected, onSelect }: {
  components: Component[];
  connections: Connection[];
  selected?: number;
  onSelect: (index: number) => void;
}) {
  const pattern = useId();
  const bounds = useMemo(() => extents(components, connections), [components, connections]);
  const [camera, setCamera] = useState<View>();
  const [grid, setGrid] = useState(true);
  const view = camera ?? bounds;
  const pan = useRef<{ point: DOMPoint; camera: View; inverse: DOMMatrix }>();

  function zoom(factor: number) {
    const width = view.width * factor;
    const height = view.height * factor;
    if (width < 3 || width > bounds.width * 4) return;
    setCamera({ width, height, x: view.x + (view.width - width) / 2, y: view.y + (view.height - height) / 2 });
  }

  function pointerDown(event: PointerEvent<SVGSVGElement>) {
    if (event.button !== 0) return;
    const matrix = event.currentTarget.getScreenCTM();
    if (!matrix) return;
    const inverse = matrix.inverse();
    pan.current = { point: new DOMPoint(event.clientX, event.clientY).matrixTransform(inverse), camera: view, inverse };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function pointerMove(event: PointerEvent<SVGSVGElement>) {
    const drag = pan.current;
    if (!drag) return;
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(drag.inverse);
    setCamera({ ...drag.camera, x: drag.camera.x + drag.point.x - point.x, y: drag.camera.y + drag.point.y - point.y });
  }

  return <Stack spacing={1}>
    <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
      <Button onClick={() => zoom(0.8)}>Zoom in</Button>
      <Button onClick={() => zoom(1.25)}>Zoom out</Button>
      <Button onClick={() => setCamera(undefined)}>Fit schematic</Button>
      <Button aria-pressed={grid} onClick={() => setGrid((show) => !show)}>Grid</Button>
    </Stack>
    <svg className="circuit-schematic" aria-label="Circuit schematic"
      viewBox={[view.x, view.y, view.width, view.height].join(' ')}
      onPointerDown={pointerDown} onPointerMove={pointerMove}
      onPointerUp={() => { pan.current = undefined; }} onPointerCancel={() => { pan.current = undefined; }}>
      <defs>
        <pattern id={pattern} width="1" height="1" patternUnits="userSpaceOnUse">
          <circle cx="0.08" cy="0.08" r="0.04" fill="#a5bbb0" />
        </pattern>
      </defs>
      {grid && <rect x={view.x - view.width} y={view.y - view.height} width={view.width * 3}
        height={view.height * 3} fill={'url(#' + pattern + ')'} />}
      {connections.map((connection, index) => (
        <polyline key={index} data-testid="schematic-wire" fill="none" vectorEffect="non-scaling-stroke"
          stroke={connection.domain === 'CONTROL' ? '#754698' : '#556a63'} strokeWidth="1.7"
          points={(connection.points ?? []).map((pair) => pair.join(',')).join(' ')} />
      ))}
      {components.map((component, index) => {
        const [x, y] = component.position ?? [0, 0];
        const symbol = component.domain === 'circuit' ? symbols[component.type ?? 0] : undefined;
        const label = component.name ?? 'Component ' + (index + 1);
        return <g key={index} role="button" tabIndex={0} aria-label={label} aria-pressed={selected === index}
          data-testid="schematic-component" transform={'translate(' + x + ',' + y + ')'}
          className="schematic-component" onPointerDown={(event) => event.stopPropagation()}
          onClick={() => onSelect(index)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              onSelect(index);
            }
          }}>
          <rect x="-1.35" y="-1.05" width="2.7" height="2.1" rx=".18"
            fill={selected === index ? '#c7eade' : symbol ? 'transparent' : '#e9f3ed'} />
          <g transform={'rotate(' + rotation(component.orientation) + ')'}>
            {symbol
              ? <path d={symbol} fill="none" stroke="#16352a" strokeWidth="1.6" vectorEffect="non-scaling-stroke" />
              : <rect x="-1.15" y="-.75" width="2.3" height="1.5" rx=".12"
                  fill="none" stroke="#487364" strokeWidth="1.4" vectorEffect="non-scaling-stroke" />}
          </g>
          <text x="1.12" y="-1.02" fontSize=".65" fill="#18372d">{label}</text>
        </g>;
      })}
    </svg>
  </Stack>;
}
