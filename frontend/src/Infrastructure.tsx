import { useQuery } from '@tanstack/react-query';
import { api, timestamp } from './api';
import { useText } from './i18n';

export type Sample = { labels: Record<string, string>; value: number | null; observedAt: string };
export type Measurement = { id: string; unit: string; samples: Sample[]; error: string | null };
export type MonitoringReport = {
  capturedAt: string; enabled: boolean; platform: string; grafanaUrl: string;
  measurements: Measurement[]; message: string;
};
export function formatMetric(value: number | null | undefined, unit: string): string {
  if (value == null || !Number.isFinite(value)) return '—';
  if (unit === 'bytes') return (value / 1024 / 1024).toFixed(1) + ' MiB';
  if (unit === 'seconds') return (value * 1000).toFixed(1) + ' ms';
  return value.toLocaleString('en-US', { maximumFractionDigits: 3 });
}
export function sampleStale(sample: Sample, now = Date.now()): boolean {
  const time = Date.parse(sample.observedAt);
  return !Number.isFinite(time) || now - time > 45000 || time - now > 45000;
}

export default function Infrastructure({ auto }: { auto: boolean }) {
  const t = useText();
  const query = useQuery({ queryKey: ['monitoring'], queryFn: () => api<MonitoringReport>('/api/monitoring', undefined, false),
    refetchInterval: auto ? 15000 : false, retry: false });
  const report = query.data;
  const measurement = (id: string) => report?.measurements.find(item => item.id === id);
  const samples = (id: string) => measurement(id)?.samples || [];
  const value = (id: string) => samples(id)[0]?.value;
  const targetSamples = samples('targets');
  const appTargets = targetSamples.filter(sample => sample.labels.job === 'balance-app');
  const exporter = report?.platform === 'docker' ? 'cadvisor' : 'kube-state-metrics';
  const exporterTargets = targetSamples.filter(sample => sample.labels.job === exporter);
  const unhealthy = (targets: Sample[]) => !targets.length || targets.some(sample => sample.value !== 1 || sampleStale(sample));
  const oldReport = !!report && Date.now() - Date.parse(report.capturedAt) > 45000;
  const appUnknown = query.isError || oldReport || unhealthy(appTargets);
  const infraUnknown = query.isError || oldReport || unhealthy(exporterTargets);
  const scalar = (id: string) => appUnknown ? '—' : formatMetric(value(id), measurement(id)?.unit || '');
  const lookup = (id: string, key: string, label: string) => samples(id).find(sample => sample.labels[key] === label)?.value;
  const dashboard = report?.grafanaUrl && /^https?:\/\//.test(report.grafanaUrl) ? report.grafanaUrl : null;
  return <section className="panel education infrastructure" aria-label={t('Infrastructure monitoring', 'پایش زیرساخت')}>
    <div className="monitoring-heading"><div><h2>{t('Infrastructure monitoring', 'پایش زیرساخت')}</h2>
      <p>{t('Deployment', 'محیط اجرا')}: <bdi>{report?.platform || '—'}</bdi> · Prometheus · {t('15s refresh', 'به‌روزرسانی ۱۵ ثانیه‌ای')}</p></div>
      <div className="heading-actions"><button className="secondary" onClick={() => void query.refetch()} disabled={query.isFetching}>{t('Refresh monitoring', 'تازه‌سازی پایش')}</button>
        {dashboard && <a href={dashboard} target="_blank" rel="noreferrer">{t('Open Grafana', 'باز کردن Grafana')} ↗</a>}</div></div>
    {query.isPending && <p role="status">{t('Loading monitoring…', 'در حال دریافت سنجه‌ها…')}</p>}
    {query.isError && <p className="notice error" role="alert">{t('Monitoring API unavailable. Previous observations are stale.', 'رابط پایش در دسترس نیست. مشاهدات قبلی قدیمی هستند.')}</p>}
    {report && !report.enabled && <p className="notice">{t('Monitoring is not configured. Start the monitoring services and enable MONITORING_ENABLED on the backend.', 'پایش پیکربندی نشده است. سرویس‌های پایش و MONITORING_ENABLED را در سرور فعال کنید.')}</p>}
    {report?.enabled && <>
      <p><bdi>{timestamp(report.capturedAt)}</bdi> · {oldReport || query.isError ? t('STALE', 'قدیمی') : auto ? t('Polling enabled', 'به‌روزرسانی فعال') : t('Polling paused', 'به‌روزرسانی متوقف')}
        {' · '}{t('Empty results mean unknown. Rates need at least two scrapes. Up to 200 series per metric.', 'نتیجه خالی نامشخص است. نرخ‌ها حداقل به دو نمونه نیاز دارند. حداکثر ۲۰۰ سری برای هر سنجه.')}</p>
      {appUnknown && <p className="notice error">{t('App scrape is missing, down, or stale; application metrics are unavailable.', 'نمونه‌برداری برنامه غایب، قطع یا قدیمی است؛ سنجه‌های برنامه در دسترس نیستند.')}</p>}
      <div className="metrics">{[
        [t('HTTP requests / s', 'درخواست HTTP در ثانیه'), 'requests'],
        [t('HTTP 5xx / s', 'خطای HTTP 5xx در ثانیه'), 'errors'],
        [t('HTTP p95 · 2m', 'صدک ۹۵ تأخیر · ۲ دقیقه'), 'latency'],
        [t('JVM heap · all replicas', 'حافظه JVM · همه نمونه‌ها'), 'heap'],
        [t('Active JDBC connections', 'اتصال‌های فعال JDBC'), 'connections'],
        [t('Shared outbox backlog', 'صف مشترک رویدادها'), 'outbox'],
        [t('Publisher failures / s', 'خطای ارسال در ثانیه'), 'publishFailures'],
      ].map(([label, id]) => <div className="metric" key={id}><div>{label}</div><strong dir="ltr">{scalar(id)}</strong>
        {measurement(id)?.error && <small>{t('Query unavailable', 'پرس‌وجو در دسترس نیست')}</small>}</div>)}</div>
      <h3>{t('Scrape targets', 'هدف‌های نمونه‌برداری')}</h3>
      {!targetSamples.length && <p className="notice">{t('No target observations. Check Prometheus connectivity and scrape configuration.', 'مشاهده‌ای از هدف‌ها نیست. اتصال و تنظیمات Prometheus را بررسی کنید.')}</p>}
      <div className="table-scroll"><table><thead><tr><th>Job</th><th>{t('Instance', 'نمونه')}</th><th>{t('Scrape status', 'وضعیت نمونه‌برداری')}</th><th>UTC</th></tr></thead><tbody>{targetSamples.map(sample => <tr key={sample.labels.job + sample.labels.instance}>
        <td><bdi>{sample.labels.job}</bdi></td><td><bdi>{sample.labels.instance}</bdi></td><td>{query.isError || oldReport || sampleStale(sample) ? t('Stale', 'قدیمی') : sample.value === 1 ? t('Up', 'متصل') : t('Down / unknown', 'قطع / نامشخص')}</td><td dir="ltr">{timestamp(sample.observedAt)}</td></tr>)}</tbody></table></div>
      {report.platform !== 'local' && <><h3>{report.platform === 'docker' ? t('Docker containers', 'کانتینرهای Docker') : t('Kubernetes pods', 'پادهای Kubernetes')}</h3>
        {infraUnknown && <p className="notice error">{t('Infrastructure exporter is missing, down, or stale. Container and pod health is unknown.', 'صادرکننده زیرساخت غایب، قطع یا قدیمی است. وضعیت کانتینرها و پادها نامشخص است.')}</p>}
        {report.platform === 'docker' ? <>
          <p>{t('cAdvisor observations for this Compose project. Last seen is not a Docker healthcheck or restart count.', 'مشاهدات cAdvisor برای این پروژه Compose. آخرین مشاهده معادل بررسی سلامت یا شمار راه‌اندازی مجدد Docker نیست.')}</p>
          <div className="table-scroll"><table><thead><tr><th>{t('Service / container', 'سرویس / کانتینر')}</th><th>{t('Last seen · UTC', 'آخرین مشاهده · UTC')}</th><th>CPU cores · 2m</th><th>{t('Working memory', 'حافظه مصرفی')}</th></tr></thead><tbody>{samples('containers').map(sample => <tr key={sample.labels.name}>
            <td><bdi>{sample.labels.service}<small>{sample.labels.name}</small></bdi></td><td dir="ltr">{infraUnknown || sample.value == null ? '—' : timestamp(new Date(sample.value * 1000).toISOString())}</td>
            <td dir="ltr">{formatMetric(infraUnknown ? null : lookup('containerCpu', 'name', sample.labels.name), 'cores')}</td><td dir="ltr">{formatMetric(infraUnknown ? null : lookup('containerMemory', 'name', sample.labels.name), 'bytes')}</td></tr>)}</tbody></table></div>
          {!samples('containers').length && <p className="empty">{t('No container observations. Check cAdvisor mounts, Docker runtime support, and project labels.', 'مشاهده کانتینر موجود نیست. اتصال‌ها، پشتیبانی Docker و برچسب پروژه را بررسی کنید.')}</p>}
        </> : <>
          <div className="table-scroll"><table><thead><tr><th>Pod</th><th>{t('Readiness', 'آمادگی')}</th><th>{t('Container restarts', 'راه‌اندازی مجدد کانتینرها')}</th></tr></thead><tbody>{samples('pods').map(sample => <tr key={sample.labels.pod}>
            <td><bdi>{sample.labels.pod}</bdi></td><td>{infraUnknown || sample.value == null ? '—' : sample.value === 1 ? t('Ready', 'آماده') : t('Not ready', 'آماده نیست')}</td><td>{formatMetric(infraUnknown ? null : lookup('restarts', 'pod', sample.labels.pod), '')}</td></tr>)}</tbody></table></div>
          <h3>{t('Workload replicas · ready / desired', 'نمونه‌های بار کاری · آماده / درخواستی')}</h3><div className="table-scroll"><table><thead><tr><th>{t('Workload', 'بار کاری')}</th><th>{t('Ready / desired', 'آماده / درخواستی')}</th></tr></thead><tbody>{samples('desiredReplicas').map(sample => <tr key={sample.labels.workload}><td><bdi>{sample.labels.workload}</bdi></td><td dir="ltr">{formatMetric(infraUnknown ? null : lookup('readyReplicas', 'workload', sample.labels.workload), '')} / {formatMetric(infraUnknown ? null : sample.value, '')}</td></tr>)}</tbody></table></div>
          {!samples('pods').length && <p className="empty">{t('No pod observations. Check namespace and exporter RBAC.', 'مشاهده پاد موجود نیست. فضای نام و مجوزهای صادرکننده را بررسی کنید.')}</p>}
        </>}</>}
      {report.measurements.some(item => item.error) && <p className="notice error">{t('Some metric queries failed. Missing values are shown as —; inspect Prometheus and exporter logs.', 'برخی پرس‌وجوها ناموفق بودند. مقادیر ناموجود با — نمایش داده می‌شوند؛ گزارش‌های Prometheus و صادرکننده را بررسی کنید.')}</p>}
    </>}
  </section>;
}
