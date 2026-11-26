import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';

async function gridPoint(grid: Locator, x: number, y: number) {
  return grid.evaluate((node, point) => {
    const matrix = (node as SVGSVGElement).getScreenCTM()!;
    const screen = new DOMPoint(point.x, point.y).matrixTransform(matrix);
    return { x: screen.x, y: screen.y };
  }, { x, y });
}

async function place(page: Page, label: string, x: number, y: number) {
  await page.getByRole('button', { name: label, exact: true }).click();
  const point = await gridPoint(page.getByLabel('Schematic drawing grid'), x, y);
  await page.mouse.click(point.x, point.y);
}

test('draws, saves, reopens, and simulates an RC low-pass circuit', async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto('/circuits');
  await page.getByLabel('API key').fill(process.env.GECKO_TEST_API_KEY ?? 'gecko-web-test-key');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('link', { name: 'New schematic', exact: true }).click();
  await page.getByLabel('Filename', { exact: true }).fill('rc-web.ipes');

  const grid = page.getByLabel('Schematic drawing grid');
  await grid.scrollIntoViewIfNeeded();
  const source = await gridPoint(grid, 5, 12);
  const box = (await grid.boundingBox())!;
  await page.getByRole('button', { name: 'Voltage source', exact: true }).dragTo(grid, {
    targetPosition: { x: source.x - box.x, y: source.y - box.y },
  });
  await expect(page.getByRole('button', { name: 'V1', exact: true })).toBeVisible();
  await place(page, 'Resistor', 12, 6);
  await place(page, 'Capacitor', 18, 12);
  await place(page, 'Ground', 9, 18);
  for (const [from, to] of [
    ['V1 terminal 1', 'R2 terminal 1'],
    ['R2 terminal 2', 'C3 terminal 1'],
    ['V1 terminal 2', 'GND4 terminal 1'],
    ['C3 terminal 2', 'GND4 terminal 1'],
  ]) {
    await page.getByRole('button', { name: from, exact: true }).click();
    await page.getByRole('button', { name: to, exact: true }).click();
  }
  await expect(page.getByTestId('draft-wire')).toHaveCount(4);

  const saved = page.waitForResponse('**/api/v1/circuits/schematic');
  await page.getByRole('button', { name: 'Save circuit', exact: true }).click();
  expect((await saved).status()).toBe(201);
  await expect(page.getByRole('heading', { name: 'Circuit editor' })).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download .ipes', exact: true }).click();
  const exported = await download;
  expect(exported.suggestedFilename()).toBe('rc-web.ipes');
  await page.getByRole('link', { name: 'Circuits', exact: true }).click();
  const reopened = page.waitForResponse('**/api/v1/circuits/parse');
  await page.getByLabel('Upload circuit').setInputFiles({
    name: 'rc-web.ipes', mimeType: 'application/octet-stream', buffer: await readDownload(exported),
  });
  expect((await reopened).status()).toBe(201);
  await expect(page.getByTestId('schematic-wire')).toHaveCount(4);

  const outputResponse = page.waitForResponse('**/api/v1/simulations/*/results/V_n2');
  const timesResponse = page.waitForResponse('**/api/v1/simulations/*/results/time');
  await page.getByRole('button', { name: 'Run simulation', exact: true }).click();
  await page.getByRole('checkbox', { name: 'V_n2', exact: true }).check({ timeout: 30_000 });
  const [output, time] = await Promise.all([outputResponse, timesResponse]);
  expect(output.ok() && time.ok()).toBeTruthy();
  const { data: values } = await output.json() as { data: number[] };
  const { data: times } = await time.json() as { data: number[] };
  expect(values.length).toBeGreaterThan(100);
  expect(values.length).toBe(times.length);
  // Compare the saved 1 kΩ, 1 µF circuit with the 5 V step response.
  const relativeErrors = times.flatMap((seconds, index) => {
    if (seconds < 0.0005) return [];
    const expected = 5 * (1 - Math.exp(-seconds / 0.001));
    return [Math.abs(values[index] - expected) / expected];
  });
  expect(relativeErrors.length).toBeGreaterThan(100);
  const maximum = Math.max(...relativeErrors);
  console.log('RC maximum relative error after 0.5 ms: ' + (maximum * 100).toFixed(4) + '%');
  expect(maximum).toBeLessThan(0.01);
});

test('rejects a conflicting elbow before it becomes a wire', async ({ page }) => {
  await page.goto('/circuits/new');
  await page.getByLabel('API key').fill(process.env.GECKO_TEST_API_KEY ?? 'gecko-web-test-key');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await place(page, 'Resistor', 4, 6);
  await place(page, 'Resistor', 11, 11);
  await place(page, 'Capacitor', 11, 6);
  const first = page.getByRole('button', { name: 'R1 terminal 1', exact: true });
  const second = page.getByRole('button', { name: 'R2 terminal 1', exact: true });
  await first.click();
  await second.click();
  await expect(page.getByRole('alert')).toContainText('different nets share a grid point');
  await expect(page.getByTestId('draft-wire')).toHaveCount(0);
  await second.click();
  await first.click();
  await expect(page.getByTestId('draft-wire')).toHaveCount(1);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

async function readDownload(download: import('@playwright/test').Download) {
  const { readFile } = await import('node:fs/promises');
  return readFile((await download.path())!);
}
