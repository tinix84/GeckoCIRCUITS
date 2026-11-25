import { expect, test } from '@playwright/test';

test('signs in, uploads the buck fixture, and lists it', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('API key').fill(process.env.GECKO_TEST_API_KEY ?? 'gecko-web-test-key');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Recent simulations' })).toBeVisible();
  await page.getByRole('link', { name: 'Circuits' }).click();
  await page.getByLabel('Upload circuit').setInputFiles('../src/test/resources/ipes/education/buck_simple.ipes');
  await expect(page.getByRole('heading', { name: 'Circuit editor' })).toBeVisible();
  await page.getByRole('link', { name: 'Circuits' }).click();
  await expect(page.getByRole('link', { name: 'buck_simple.ipes' }).first()).toBeVisible();
});
