import { expect, test } from '@playwright/test';

// Deterministic frontend contract tests. These fixtures are never shipped as live data.
test.beforeEach(async ({ page }) => {
  await page.route('**/api/monitoring', route => route.fulfill({ json: {
    capturedAt: new Date().toISOString(), enabled: false, platform: 'local',
    grafanaUrl: '', measurements: [], message: 'Monitoring is not configured.',
  } }));
  await page.route('**/api/console/snapshot?*', route => route.fulfill({ json: {
    capturedAt: new Date().toISOString(), accountCount: 42, totalBalance: '9007199254740993.125000000000000001', receiptCount: 1,
    pendingEvents: 2, kafkaEnabled: true, labEnabled: true,
    accounts: [{ id: 'A', balance: '9007199254740993.125000000000000001' }, { id: 'B', balance: '10.25' }],
    receipts: [{ transactionId: 'tx-existing', type: 'TRANSFER', source: 'A', destination: 'B', amount: '0.125000000000000001', processedAt: '2026-11-17T08:00:00Z' }], filteredReceiptCount: 1,
  } }));
});

test('Persian default, both themes, all sections fit at 320px and desktop', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'fa');
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.getByText('9,007,199,254,740,993.125000000000000001', { exact: true })).toBeVisible();
  for (const width of [320, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const theme of ['light', 'dark']) {
      await page.getByLabel('پوسته').selectOption(theme);
      for (const id of ['overview', 'accounts', 'transfers', 'path', 'structure', 'monitoring', 'lab', 'api']) {
        await page.locator('nav a[href="#' + id + '"]').click();
        await expect(page.locator('h1')).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      }
      await page.locator('nav a[href="#overview"]').click();
      await page.screenshot({ path: `test-results/frontend-${width}-${theme}.png`, fullPage: true });
    }
  }
  await page.getByLabel('زبان').selectOption('en');
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByRole('link', { name: 'Structure', exact: true }).click();
  await page.getByRole('button', { name: 'Redis', exact: true }).click();
  await expect(page.locator('.component-detail')).toContainText('durable JDBC receipts remain authoritative');
  expect(errors).toEqual([]);
});

test('decimal request, original retry, fresh ID and explicit conflict', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('balance-language', 'en'));
  const requests: string[] = [];
  await page.route('**/accounts/A/transfer', async route => {
    requests.push(route.request().postData()!);
    await route.fulfill({ body: '{"accountId":"A","balance":9007199254740993.000000000000000001}', contentType: 'application/json' });
  });
  await page.goto('/#transfers');
  await page.getByLabel('From account').fill('A');
  await page.getByLabel('To account').fill('B');
  await page.getByLabel('Amount · exact units').fill('9007199254740993.1234567890123456789');
  const originalID = await page.getByLabel('Transaction ID', { exact: true }).inputValue();
  await page.getByRole('button', { name: 'Submit transfer', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Acknowledged');
  expect(requests[0]).toContain('"amount":9007199254740993.1234567890123456789');
  await page.getByLabel('Amount · exact units').fill('1.25');
  expect(await page.getByLabel('Transaction ID', { exact: true }).inputValue()).not.toBe(originalID);
  await page.getByRole('button', { name: 'Retry original payload' }).click();
  await expect.poll(() => requests.length).toBe(2);
  expect(requests[1]).toBe(requests[0]);
  await page.getByLabel('Deliberate conflict').check();
  await page.getByLabel('Transaction ID', { exact: true }).fill(originalID);
  await page.getByLabel('Amount · exact units').fill('2.50');
  await expect(page.getByLabel('Transaction ID', { exact: true })).toHaveValue(originalID);
  await page.getByRole('link', { name: 'API inspector', exact: true }).click();
  await page.getByRole('button', { name: 'Inspect', exact: true }).first().click();
  await expect(page.getByRole('dialog')).toContainText('9007199254740993.000000000000000001');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Inspect', exact: true }).first()).toBeFocused();
});

test('inline errors, uncertain outcome, stale snapshot and truthful evidence', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('balance-language', 'en'));
  await page.route('**/accounts/A/transfer', route => route.fulfill({ status: 400, json: { fieldErrors: { amount: ['must be positive'] } } }));
  await page.goto('/#transfers');
  await page.getByLabel('From account').fill('A');
  await page.getByLabel('To account').fill('B');
  await page.getByRole('button', { name: 'Submit transfer', exact: true }).click();
  await expect(page.getByLabel('Amount · exact units')).toHaveAttribute('aria-invalid', 'true');
  await expect(page.locator('#error-amount')).toHaveText('must be positive');
  await page.unroute('**/accounts/A/transfer');
  await page.route('**/accounts/A/transfer', route => route.abort());
  await page.getByRole('button', { name: 'Retry original payload' }).click();
  await expect(page.getByRole('alert')).toContainText('Outcome unknown');
  await page.getByRole('link', { name: 'Technical monitoring', exact: true }).click();
  await expect(page.getByText('Configured enabled; broker availability and actual delivery unknown.')).toBeVisible();
  await page.unroute('**/api/console/snapshot?*');
  await page.route('**/api/console/snapshot?*', route => route.abort());
  await page.getByRole('button', { name: 'Refresh data' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Showing the last successful snapshot' })).toBeVisible();
  await expect(page.locator('.connection')).toContainText('Stale');
});

test('dialog traps focus, validates account IDs and restores focus', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('balance-language', 'en'));
  await page.goto('/#accounts');
  const opener = page.getByRole('button', { name: 'New account', exact: true });
  await opener.click();
  const dialog = page.getByRole('dialog');
  await expect(page.getByRole('button', { name: 'Close details' })).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(dialog.getByRole('button', { name: 'Create account', exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Close details' })).toBeFocused();
  await page.getByLabel('Account ID', { exact: true }).fill('../invalid');
  expect(await page.getByLabel('Account ID', { exact: true }).evaluate((element: HTMLInputElement) => element.validity.patternMismatch)).toBe(true);
  await page.keyboard.press('Escape');
  await expect(opener).toBeFocused();
});

test('selected account seeds the draft and lab shows actual report assertions', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('balance-language', 'en'));
  const report = { id: 'fixture-run', state: 'COMPLETED', scenario: 'COMPETING', startedAt: '2026-11-17T08:00:00Z', requested: 2,
    acknowledged: 1, rejected: 1, errors: 0, committedReceipts: 1, balances: { A: '20.000', B: '80.000' }, conserved: true,
    nonnegative: true, expectedEffect: true, durationMs: 12, p95Ms: 4, attempts: [], message: 'Fixture report' };
  await page.route('**/api/demo/runs', route => route.fulfill({ status: 202, json: report }));
  await page.route('**/api/demo/runs/fixture-run', route => route.fulfill({ json: report }));
  await page.goto('/#accounts');
  await page.getByRole('button', { name: 'View history' }).first().click();
  await expect(page.getByLabel('From account')).toHaveValue('A');
  await page.getByRole('link', { name: 'Concurrency lab', exact: true }).click();
  await expect(page.getByText('Shared-stack demonstration', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Run experiment', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Experiment results' })).toBeVisible();
  await expect(page.locator('.checks .pass')).toHaveCount(3);
  await expect(page.locator('.balance-results')).toContainText('20.000');
  await expect(page.getByText('Reported state', { exact: false })).toContainText('COMPLETED');
});
