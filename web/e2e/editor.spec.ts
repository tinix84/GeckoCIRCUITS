import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

async function runAndGetOutput(page: Page) {
  const output = page.waitForResponse('**/api/v1/simulations/*/results/u2', { timeout: 45_000 });
  await page.getByRole('button', { name: 'Run simulation' }).click();
  const response = await output;
  expect(response.ok()).toBeTruthy();
  const { data } = await response.json();
  await page.getByRole('checkbox', { name: 'u2' }).check();
  await expect(page.getByRole('img', { name: /Waveforms: .*u2/ })).toBeVisible();
  return data as number[];
}

test('renders the buck circuit and changes its simulated output ripple when L is saved', async ({ page }) => {
  test.setTimeout(90_000);
  await page.goto('/circuits');
  await page.getByLabel('API key').fill(process.env.GECKO_TEST_API_KEY ?? 'gecko-web-test-key');
  await page.getByRole('button', { name: 'Sign in' }).click();
  const opened = page.waitForResponse('**/api/v1/circuits/*/components');
  await page.getByLabel('Upload circuit').setInputFiles('../src/test/resources/ipes/education/buck_simple.ipes');
  const response = await opened;
  expect(response.ok()).toBeTruthy();
  const model = await response.json();
  const editorUrl = page.url();
  await expect(page.getByTestId('schematic-component')).toHaveCount(model.components.length);
  expect(model.connections.length).toBeGreaterThan(0);
  await expect(page.getByTestId('schematic-wire')).toHaveCount(model.connections.length);

  const original = await runAndGetOutput(page);
  await page.goBack();
  await expect(page).toHaveURL(editorUrl);
  await page.getByRole('button', { name: 'L.1', exact: true }).click();
  const inductance = page.getByRole('spinbutton', { name: 'inductance', exact: true });
  await expect(inductance).toHaveValue('0.0003');
  await inductance.fill('0.0006');
  await page.getByRole('button', { name: 'Save parameters' }).click();
  await expect(page.getByRole('status')).toHaveText('Parameters saved.');
  const adjusted = await runAndGetOutput(page);
  const ripple = (data: number[]) => {
    const lastPeriod = data.slice(-29);
    return Math.max(...lastPeriod) - Math.min(...lastPeriod);
  };
  expect(Math.abs(ripple(adjusted) - ripple(original))).toBeGreaterThan(ripple(original) * 0.10);
});
