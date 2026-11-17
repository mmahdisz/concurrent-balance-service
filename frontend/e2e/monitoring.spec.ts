import { expect, test, type Page } from '@playwright/test';

function metric(id: string, values: { labels: Record<string, string>; value: number | null; observedAt?: string }[]) {
  return { id, unit: id === 'heap' || id === 'containerMemory' ? 'bytes' : id === 'latency' ? 'seconds' : '', error: null,
    samples: values.map(sample => ({ observedAt: new Date().toISOString(), ...sample })) };
}
async function setup(page: Page, platform: string, enabled = true, stale = false) {
  await page.addInitScript(() => localStorage.setItem('balance-language', 'en'));
  await page.route('**/api/console/snapshot?*', route => route.fulfill({ json: {
    capturedAt: new Date().toISOString(), accountCount: 0, totalBalance: '0', receiptCount: 0,
    pendingEvents: 0, kafkaEnabled: true, labEnabled: false, accounts: [], receipts: [], filteredReceiptCount: 0,
  } }));
  await page.route('**/api/monitoring', route => route.fulfill({ json: {
    capturedAt: new Date().toISOString(), enabled, platform, grafanaUrl: 'http://localhost:3000/d/balance-lab', message: '',
    measurements: [
      metric('targets', ['balance-app', platform === 'docker' ? 'cadvisor' : 'kube-state-metrics'].map(job => ({
        labels: { job, instance: job + ':8080' }, value: 1,
        observedAt: new Date(Date.now() - (stale ? 60000 : 0)).toISOString(),
      }))),
      metric('heap', [{ labels: {}, value: 1048576 }]), metric('requests', [{ labels: {}, value: 0 }]),
      metric('latency', [{ labels: {}, value: null }]),
      metric('containers', [{ labels: { service: 'app', name: 'lab-app-1' }, value: Date.now() / 1000 }]),
      metric('containerMemory', [{ labels: { service: 'app', name: 'lab-app-1' }, value: 2097152 }]),
      metric('containerCpu', [{ labels: { service: 'app', name: 'lab-app-1' }, value: 0.125 }]),
      metric('pods', [{ labels: { pod: 'app-123' }, value: 0 }]),
      metric('restarts', [{ labels: { pod: 'app-123' }, value: 3 }]),
      metric('desiredReplicas', [{ labels: { workload: 'app' }, value: 2 }]),
      metric('readyReplicas', [{ labels: { workload: 'app' }, value: 1 }]),
    ],
  } }));
  await page.goto('/#monitoring');
}

test('Docker observations, Grafana link, RTL and narrow layout', async ({ page }) => {
  await setup(page, 'docker');
  const panel = page.getByRole('region', { name: 'Infrastructure monitoring', exact: true });
  await expect(panel.getByText('lab-app-1', { exact: true })).toBeVisible();
  await expect(panel.getByText('2.0 MiB', { exact: true })).toBeVisible();
  await expect(panel.getByRole('link', { name: 'Open Grafana' })).toHaveAttribute('href', 'http://localhost:3000/d/balance-lab');
  await page.screenshot({ path: 'test-results/monitoring-docker.png', fullPage: true });
  await page.getByLabel('Language').selectOption('fa');
  await page.setViewportSize({ width: 320, height: 900 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/monitoring-docker-mobile.png', fullPage: true });
});

test('Kubernetes shows readiness, restarts, and replica shortfalls', async ({ page }) => {
  await setup(page, 'kubernetes');
  const panel = page.getByRole('region', { name: 'Infrastructure monitoring', exact: true });
  await expect(panel.getByRole('row').filter({ hasText: 'app-123' })).toContainText('Not ready');
  await expect(panel.getByRole('row').filter({ hasText: 'app-123' })).toContainText('3');
  await expect(panel.getByText('1 / 2', { exact: true })).toBeVisible();
  await expect(panel.getByText('Docker containers', { exact: true })).toHaveCount(0);
  await page.screenshot({ path: 'test-results/monitoring-kubernetes.png', fullPage: true });
});

test('stale exporter and app targets do not report healthy observations', async ({ page }) => {
  await setup(page, 'kubernetes', true, true);
  const panel = page.getByRole('region', { name: 'Infrastructure monitoring', exact: true });
  await expect(panel.getByText('App scrape is missing, down, or stale; application metrics are unavailable.')).toBeVisible();
  await expect(panel.getByText('1.0 MiB', { exact: true })).toHaveCount(0);
  await expect(panel.getByRole('cell', { name: 'Ready', exact: true })).toHaveCount(0);
});

test('disabled monitoring is explicit and an API outage is recoverable', async ({ page }) => {
  await setup(page, 'local', false);
  await expect(page.getByText(/Monitoring is not configured\./)).toBeVisible();
  await page.route('**/api/monitoring', route => route.fulfill({ status: 503, json: { detail: 'Unavailable' } }));
  await page.getByRole('button', { name: 'Refresh monitoring', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Monitoring API unavailable' })).toBeVisible();
});
