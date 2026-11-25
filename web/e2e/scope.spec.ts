import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

async function signIn(page: Page, path: string) {
  await page.goto(path);
  await page.getByLabel('API key').fill(process.env.GECKO_TEST_API_KEY ?? 'gecko-web-test-key');
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('plots buck output and shows the Fourier peak and downloadable CSV', async ({ page, request }) => {
  test.setTimeout(60_000);
  const token = await request.post('/api/v1/auth/token', {
    headers: { 'X-API-Key': process.env.GECKO_TEST_API_KEY ?? 'gecko-web-test-key' },
  });
  expect(token.ok()).toBeTruthy();
  const headers = { Authorization: 'Bearer ' + (await token.json()).accessToken };
  const circuit = await request.post('/api/v1/circuits/parse', {
    headers,
    multipart: { file: {
      name: 'buck_simple.ipes', mimeType: 'application/octet-stream',
      buffer: await readFile('../src/test/resources/ipes/education/buck_simple.ipes'),
    } },
  });
  expect(circuit.status()).toBe(201);
  const { circuitId } = await circuit.json();
  const job = await request.post('/api/v1/simulations', { headers, data: { circuitId } });
  expect(job.status()).toBe(201);
  const { simulationId } = await job.json();
  await signIn(page, '/simulations/' + simulationId);
  await page.getByRole('checkbox', { name: 'u2', exact: true }).check({ timeout: 30_000 });
  await expect(page.getByRole('img', { name: /Waveforms: .*u2/ })).toBeVisible();
  await expect(page.getByLabel('Cursor delta time')).toHaveText(/^Δt [0-9.]+(?:e[+-]?\d+)? s$/i);
  await expect(page.getByLabel('Cursor delta value')).toHaveText(/ΔV \(u2\) -?[0-9.]+/);

  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export CSV' }).click();
  const csv = await readFile((await (await download).path())!, 'utf8');
  const rows = csv.trim().split(/\r?\n/);
  expect(rows[0]).toContain('"u2"');
  expect(rows.length).toBeGreaterThanOrEqual(101);
  const pngDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export PNG' }).click();
  const png = await readFile((await (await pngDownload).path())!);
  expect(png.subarray(1, 4).toString()).toBe('PNG');

  await page.getByRole('tab', { name: 'FFT' }).click();
  await page.getByRole('combobox', { name: 'Fourier signal' }).click();
  await page.getByRole('option', { name: 'u2', exact: true }).click();
  const analyzed = page.waitForResponse('**/api/v1/analysis/fourier?*');
  await page.getByRole('button', { name: 'Calculate spectrum' }).click();
  expect((await analyzed).ok()).toBeTruthy();
  const peaks = page.getByTestId('fourier-peak');
  const bin = page.getByLabel('Fourier bin width');
  await expect(peaks.first()).toBeVisible();
  const peakFrequencies = (await peaks.allTextContents()).map((text) => Number(text.trim().split(' ')[0]));
  const binHz = Number((await bin.innerText()).split(' ')[2]);
  // The buck fixture records its 35 kHz converter output as u2.
  expect(Math.min(...peakFrequencies.map((frequency) => Math.abs(frequency - 35_000)))).toBeLessThanOrEqual(binHz);
});

test('pans a 500,000-sample trace and measures frame time', async ({ page }) => {
  test.setTimeout(60_000);
  const points = 500_000;
  const times = Array.from({ length: points }, (_, index) => index / 1_000_000);
  const samples = times.map((time) => Math.sin(time * 40_000 * Math.PI));
  await page.route(/\/api\/v1\/simulations\/benchmark(?:\/.*)?$/, async (route) => {
    const name = new URL(route.request().url()).pathname.split('/').at(-1);
    const response = name === 'benchmark' ? { status: 'COMPLETED', results: { time: [], Vout: [] } }
      : { data: name === 'time' ? times : samples };
    await route.fulfill({ json: response });
  });
  await signIn(page, '/simulations/benchmark');
  await expect(page.getByRole('img', { name: 'Waveforms: Vout' })).toBeVisible({ timeout: 30_000 });
  const bounds = await page.locator('.u-over').boundingBox();
  if (!bounds) throw new Error('The waveform overlay is missing.');
  const y = bounds.y + bounds.height / 2;
  await page.mouse.move(bounds.x + bounds.width * 0.05, y);
  await page.mouse.down();
  await page.mouse.move(bounds.x + bounds.width * 0.95, y, { steps: 4 });
  await page.mouse.up();
  const benchmark = await page.evaluate(async () => {
    const buttons = [...document.querySelectorAll<HTMLButtonElement>('button')];
    const pan = [buttons.find((button) => button.textContent === 'Pan right'),
      buttons.find((button) => button.textContent === 'Pan left')];
    const frame = () => new Promise<number>((resolve) => requestAnimationFrame(resolve));
    const canvas = document.querySelector<HTMLCanvasElement>('.uplot canvas')!;
    const pixels = canvas.getContext('2d')!.getImageData(0, 0, canvas.width, canvas.height).data;
    const traceVisible = pixels.some((red, index) => index % 4 === 0 && pixels[index + 3] > 0 &&
      pixels[index + 1] > red + 25 && pixels[index + 2] > red + 20);
    const idle = [];
    const panning = [];
    for (let index = 0; index < 10; index += 1) {
      const inactive = await frame();
      idle.push((await frame()) - inactive);
      const start = await frame();
      pan[index % 2]!.click();
      panning.push((await frame()) - start);
    }
    return { idle, panning, traceVisible };
  });
  const sorted = benchmark.panning.slice().sort((a, b) => a - b);
  const idle = benchmark.idle.slice().sort((a, b) => a - b);
  const message = '500k-sample pan frame time: median ' + sorted[5].toFixed(1)
    + ' ms, max ' + sorted.at(-1)!.toFixed(1) + ' ms; idle median ' + idle[5].toFixed(1) + ' ms';
  await test.info().attach('Pan frame time', { body: message, contentType: 'text/plain' });
  console.log(message);
  expect(benchmark.traceVisible).toBeTruthy();
  expect(benchmark.panning).toHaveLength(10);
});
