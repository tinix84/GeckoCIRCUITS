export type Trace = { name: string; values: number[] };
export type CursorPair = [number, number];

export function chartCsv(time: number[], traces: Trace[]) {
  const labels = ['time_s', ...traces.map(({ name }) => '"' + name.replaceAll('"', '""') + '"')];
  const rows = [labels.join(',')];
  for (let index = 0; index < time.length; index += 1) {
    rows.push([time[index], ...traces.map(({ values }) => values[index] ?? '')].join(','));
  }
  return rows.join('\r\n') + '\r\n';
}

export function closestSample(time: number[], target: number) {
  let first = 0;
  let last = time.length - 1;
  while (first < last) {
    const middle = (first + last) >>> 1;
    if (time[middle] < target) first = middle + 1;
    else last = middle;
  }
  if (first > 0 && target - time[first - 1] <= time[first] - target) return first - 1;
  return first;
}

export function reducedRows(time: number[], traces: Trace[], bucketSize: number): [number[], ...number[][]] {
  if (bucketSize <= 1) return [time, ...traces.map(({ values }) => values)];

  // Retain the extrema of each channel and the two time boundaries.
  const indexes = [0];
  for (let start = 1; start < time.length - 1; start += bucketSize) {
    const end = Math.min(time.length - 1, start + bucketSize);
    const extrema = new Set<number>();
    for (const { values } of traces) {
      let minimum = start;
      let maximum = start;
      for (let index = start + 1; index < end; index += 1) {
        if (values[index] < values[minimum]) minimum = index;
        if (values[index] > values[maximum]) maximum = index;
      }
      extrema.add(minimum);
      extrema.add(maximum);
    }
    indexes.push(...[...extrema].sort((first, second) => first - second));
  }
  indexes.push(time.length - 1);
  return [indexes.map((index) => time[index]), ...traces.map(({ values }) => indexes.map((index) => values[index]))];
}

export function numericSeries(value: unknown): number[] {
  if (!Array.isArray(value) || !value.every((point) => typeof point === 'number' && Number.isFinite(point))) {
    throw new Error('The signal contains invalid samples.');
  }
  return value;
}
