import { expect, test } from 'vitest';
import { chartCsv, closestSample, numericSeries } from './scopeData';

test('uses the nearest recorded sample for cursor measurements', () => {
  const time = [0, 0.2, 0.8, 1];
  expect(closestSample(time, -1)).toBe(0);
  expect(closestSample(time, 0.41)).toBe(1);
  expect(closestSample(time, 0.59)).toBe(2);
  expect(closestSample(time, 2)).toBe(3);
});

test('exports one row per time sample and quotes channel labels', () => {
  expect(chartCsv([0, 1], [{ name: 'V"out', values: [5, 6] }]))
    .toBe('time_s,"V""out"\r\n0,5\r\n1,6\r\n');
  expect(() => numericSeries([0, null])).toThrow('invalid samples');
});
