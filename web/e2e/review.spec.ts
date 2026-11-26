import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

async function signIn(page: Page, path: string) {
  await page.goto(path);
  await page.getByLabel('API key').fill(process.env.GECKO_TEST_API_KEY ?? 'gecko-web-test-key');
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('draws both native horizontal source orientations', async ({ page }) => {
  await page.route('**/api/v1/circuits/directions/components', (route) => route.fulfill({ json: {
    components: [502, 504].map((orientation, index) => ({
      orientation, name: 'Source ' + orientation, position: [index * 6, 4], domain: 'circuit', type: 4,
    })),
  } }));
  await page.route('**/api/v1/circuits/directions/info', (route) => route.fulfill({ json: { filename: 'directions.ipes' } }));
  await signIn(page, '/circuits/directions');
  for (const [code, angle] of [[502, -90], [504, 90]]) {
    await expect(page.getByRole('button', { name: 'Source ' + code }).locator('g'))
      .toHaveAttribute('transform', 'rotate(' + angle + ')');
  }
});

test('keeps the refreshed session when an old request returns 401', async ({ page }) => {
  await page.route('**/api/v1/auth/token', (route) => route.fulfill({
    json: { accessToken: 'old', refreshToken: 'refresh', expiresIn: 1 },
  }));
  await page.route('**/api/v1/auth/refresh', (route) => route.fulfill({
    json: { accessToken: 'new', refreshToken: 'refreshed', expiresIn: 3600 },
  }));
  let release!: () => void;
  const delayed = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/v1/circuits?*', async (route) => {
    expect(route.request().headers().authorization).toBe('Bearer old');
    await delayed;
    await route.fulfill({ status: 401 });
  });
  const refreshed = page.waitForResponse('**/auth/refresh');
  await signIn(page, '/circuits');
  await (await refreshed).finished();
  await page.evaluate(() => new Promise(requestAnimationFrame));
  release();
  await expect(page.getByRole('alert')).toContainText('HTTP 401');
  await expect(page.getByRole('heading', { name: 'Circuit library' })).toBeVisible();
});

test('opens a wide result and keeps its loaded trace when a selected channel is rate limited', async ({ page }) => {
  const names = Array.from({ length: 105 }, (_, index) => 'Other ' + index);
  const times = Array.from({ length: 12 }, (_, index) => index);
  const reads: string[] = [];
  await page.route('**/api/v1/simulations/wide', (route) => route.fulfill({ json: {
    status: 'COMPLETED', results: Object.fromEntries(['time', 'Vout', ...names].map((name) => [name, []])),
  } }));
  await page.route('**/api/v1/simulations/wide/results/*', (route) => {
    const name = decodeURIComponent(new URL(route.request().url()).pathname.split('/').at(-1)!);
    reads.push(name);
    if (name === 'Other 104') return route.fulfill({ status: 429 });
    return route.fulfill({ json: { data: times } });
  });
  await signIn(page, '/simulations/wide');
  const plot = page.getByRole('img', { name: 'Waveforms: Vout' });
  await expect(plot).toBeVisible();
  expect(reads).toEqual(['time', 'Vout']);
  await page.getByRole('checkbox', { name: 'Other 104', exact: true }).check();
  await expect(page.getByRole('alert')).toContainText('HTTP 429');
  await expect(plot).toBeVisible();
  expect(reads).toEqual(['time', 'Vout', 'Other 104']);
});

test('shows a 35 kHz peak from a 50 ms trace', async ({ page }) => {
  const sampleRate = 1_000_000;
  const time = Array.from({ length: 50_001 }, (_, index) => index / sampleRate);
  const data = time.map((value) => Math.sin(2 * Math.PI * 35_000 * value));
  await page.route('**/api/v1/simulations/spectrum', (route) => route.fulfill({
    json: { status: 'COMPLETED', results: { time: [], Vout: [] } },
  }));
  await page.route('**/api/v1/simulations/spectrum/results/*', (route) => route.fulfill({
    json: { data: route.request().url().endsWith('/time') ? time : data },
  }));
  await page.route('**/analysis/fourier?*', async (route) => {
    const body = route.request().postDataJSON();
    const start = Math.round(body.startTime * sampleRate);
    const end = Math.round(body.endTime * sampleRate);
    const response = await route.fetch({ postData: { data: data.slice(start, end + 1), sampleRate } });
    await route.fulfill({ response });
  });
  await signIn(page, '/simulations/spectrum');
  await page.getByRole('tab', { name: 'FFT' }).click();
  await page.getByRole('button', { name: 'Calculate spectrum' }).click();
  const firstPeak = page.getByTestId('fourier-peak').first();
  await expect(firstPeak).toBeVisible();
  const hz = Number((await firstPeak.innerText()).split(' ')[0]);
  expect(Math.abs(hz - 35_000)).toBeLessThan(125);
});
