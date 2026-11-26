import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const apiKey = process.env.GECKO_TEST_API_KEY ?? 'gecko-web-test-key';
const circuitFile = (name: string) =>
  fileURLToPath(new URL('../../docs/assets/circuits/' + name + '.ipes', import.meta.url));

async function signIn(page: Page) {
  await page.getByLabel('API key').fill(apiKey);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Circuit editor' })).toBeVisible();
}

for (const lesson of [
  { circuit: 'rc-step', component: 'R1', parameter: 'resistance', channels: ['vc', 'vin'], expected: 3.16 },
  { circuit: 'buck-open-loop', component: 'L.1', parameter: 'inductance', channels: ['Vout', 'IL'], expected: 18.82 },
  { circuit: 'buck-closed-loop', component: 'CONST.1', parameter: 'value', channels: ['Vout', 'IL'], expected: 12 },
]) {
  test('runs the ' + lesson.circuit + ' tutorial and displays the requested channels', async ({ page }) => {
    const circuit = 'https://lessons.example.test/' + lesson.circuit + '.ipes';
    await page.route(circuit, (route) => route.fulfill({
      path: circuitFile(lesson.circuit),
      headers: { 'Access-Control-Allow-Origin': '*' },
    }));
    const query = new URLSearchParams({ circuit, signals: lesson.channels.join(',') });
    await page.goto('/embed?' + query);
    await signIn(page);

    await expect(page.getByRole('navigation', { name: 'Main navigation' })).toHaveCount(0);
    await page.getByRole('button', { name: lesson.component, exact: true }).click();
    await expect(page.getByRole('spinbutton', { name: lesson.parameter, exact: true })).toBeVisible();
    const [first, second] = lesson.channels;
    const samples = page.waitForResponse((response) => response.url().endsWith('/results/' + first) && response.ok());
    await page.getByRole('button', { name: 'Run simulation' }).click();

    await expect(page.getByRole('img', { name: /Waveforms:/ })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('checkbox', { name: first, exact: true })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: second, exact: true })).toBeChecked();
    const { data } = await (await samples).json() as { data: number[] };
    const measured = lesson.circuit === 'rc-step'
      ? data[250]
      : data.slice(-1000).reduce((sum, sample) => sum + sample, 0) / 1000;
    expect(Math.abs(measured - lesson.expected)).toBeLessThan(0.1);
  });
}

test('clones a stored tutorial before saving a parameter change', async ({ page }) => {
  const response = await page.request.post('/api/v1/circuits/parse', {
    headers: { 'X-API-Key': apiKey },
    multipart: { file: {
      name: 'buck-open-loop.ipes',
      mimeType: 'application/octet-stream',
      buffer: readFileSync(circuitFile('buck-open-loop')),
    } },
  });
  expect(response.ok()).toBeTruthy();
  const { circuitId } = await response.json() as { circuitId: string };
  const original = await page.request.get('/api/v1/circuits/' + circuitId + '/components', {
    headers: { 'X-API-Key': apiKey },
  });
  expect(original.ok()).toBeTruthy();
  const originalComponents = await original.json();

  await page.goto('/embed?' + new URLSearchParams({ circuit: circuitId, signals: 'Vout,IL' }));
  await signIn(page);
  await page.getByRole('button', { name: 'L.1', exact: true }).click();
  await page.getByRole('spinbutton', { name: 'inductance', exact: true }).fill('0.0006');
  await page.getByRole('button', { name: 'Save parameters' }).click();
  await expect(page.getByRole('status')).toHaveText('Parameters saved.');

  const after = await page.request.get('/api/v1/circuits/' + circuitId + '/components', {
    headers: { 'X-API-Key': apiKey },
  });
  expect(after.ok()).toBeTruthy();
  expect(await after.json()).toEqual(originalComponents);
});
