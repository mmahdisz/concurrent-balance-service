import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, timestamp, units, type Run, type Scenario } from './api';
import { useText } from './i18n';
export default function Lab({ enabled, refresh }: { enabled: boolean; refresh: () => void }) {
  const t = useText();
  const scenarios: { id: Scenario; title: string; description: string; expected: string }[] = [
    { id: 'COMPETING', title: t('Race for the balance', 'رقابت بر سر موجودی'), description: t('Two simultaneous transfers of 80 compete for a balance of 100.', 'دو انتقال هم‌زمان ۸۰ واحدی بر سر موجودی ۱۰۰ رقابت می‌کنند.'), expected: t('One commit, one insufficient-funds rejection, final source balance 20.', 'یک ثبت موفق، یک رد به علت کمبود موجودی و موجودی نهایی مبدأ ۲۰.') },
    { id: 'DUPLICATE', title: t('One ID, many requests', 'یک شناسه، درخواست‌های متعدد'), description: t('Submit one transfer repeatedly with the same transaction ID.', 'ارسال چندباره یک انتقال با شناسه تراکنش یکسان.'), expected: t('Multiple acknowledgements; exactly one committed effect of 10 units.', 'چند تأیید؛ دقیقاً یک اثر ثبت‌شده ۱۰ واحدی.') },
    { id: 'OPPOSING', title: t('Opposite directions', 'انتقال در جهت‌های مخالف'), description: t('Send A → B and B → A concurrently, one unit at a time.', 'انتقال هم‌زمان از A به B و از B به A، هر بار یک واحد.'), expected: t('Ordered locking; conserved total of 2,000.', 'قفل‌گذاری مرتب و حفظ مجموع ۲۰۰۰.') },
    { id: 'BURST', title: t('Contention burst', 'موج رقابت'), description: t('Queue bounded transfers against the same two accounts.', 'انتقال‌های محدود در صف برای دو حساب مشترک.'), expected: t('Inspect unique commits and service-call durations under contention.', 'بررسی ثبت‌های یکتا و مدت فراخوانی سرویس هنگام رقابت.') },
  ];
  const [scenario, setScenario] = useState<Scenario>('COMPETING'), [requests, setRequests] = useState(20), [concurrency, setConcurrency] = useState(4);
  const [runId, setRunId] = useState(() => { try { return sessionStorage.getItem('balance-lab-run') || ''; } catch { return ''; } });
  const [starting, setStarting] = useState(false), [error, setError] = useState('');
  const result = useQuery({ queryKey: ['run', runId], queryFn: () => api<Run>('/api/demo/runs/' + encodeURIComponent(runId), undefined, false), enabled: !!runId,
    refetchInterval: query => query.state.data?.state === 'RUNNING' ? 700 : false });
  const r = result.data;
  useEffect(() => { if (r?.state === 'COMPLETED' || r?.state === 'FAILED') refresh(); }, [r?.state]);
  async function start() {
    setStarting(true); setError('');
    try {
      const run = await api<Run>('/api/demo/runs', { scenario, requests: scenario === 'COMPETING' ? 2 : requests, concurrency });
      setRunId(run.id); try { sessionStorage.setItem('balance-lab-run', run.id); } catch { /* In-memory report still works. */ }
    } catch (e) { setError((e as Error).message); } finally { setStarting(false); }
  }
  return <>
    {!enabled && <p className="notice error">{t('Lab disabled. Enable BALANCE_DEMO_ENABLED on the backend to run demonstrations.', 'آزمایشگاه غیرفعال است. برای اجرای نمونه‌ها BALANCE_DEMO_ENABLED را در سرور فعال کنید.')}</p>}
    <div className="scenario-grid">{scenarios.map((item, index) => <button type="button" key={item.id} aria-pressed={scenario === item.id} className={'scenario ' + (scenario === item.id ? 'active' : '')} onClick={() => setScenario(item.id)}><span className="scenario-number">0{index + 1}</span><h3>{item.title}</h3><p>{item.description}</p></button>)}</div>
    <section className="panel"><div className="panel-heading"><div><h2>{t('Configure your experiment', 'تنظیم آزمایش')}</h2><p>{scenarios.find(item => item.id === scenario)?.expected}</p></div></div>
    <div className="lab-controls"><label>{t('Requests', 'درخواست‌ها')}<input dir="ltr" type="number" min={2} max={100} value={scenario === 'COMPETING' ? 2 : requests} disabled={scenario === 'COMPETING'} onChange={event => setRequests(Number(event.target.value))} /></label>
    <label>{t('Concurrent workers', 'اجراکنندگان هم‌زمان')}<input dir="ltr" type="number" min={2} max={16} value={concurrency} onChange={event => setConcurrency(Number(event.target.value))} /></label>
    <button className="primary" onClick={start} disabled={!enabled || starting || r?.state === 'RUNNING' || (scenario !== 'COMPETING' && (requests < 2 || requests > 100 || !Number.isInteger(requests))) || concurrency < 2 || concurrency > 16 || !Number.isInteger(concurrency)}>{starting || r?.state === 'RUNNING' ? t('Experiment running…', 'آزمایش در حال اجراست…') : t('Run experiment', 'اجرای آزمایش')}</button></div>
    <p className="hint padded">{t('One run per service instance, up to 100 requests and 16 workers. Accounts and receipts persist in the shared database. Reports are temporary; no cancellation endpoint exists.', 'یک اجرا در هر نمونه سرویس، حداکثر ۱۰۰ درخواست و ۱۶ اجراکننده. حساب‌ها و رسیدها در پایگاه داده مشترک باقی می‌مانند. گزارش‌ها موقت هستند؛ رابط لغو وجود ندارد.')}</p></section>
    {(error || result.error) && <p className="notice error" role="alert">{error || (result.error as Error).message}<button className="text-button" onClick={() => result.refetch()}>{t('Check run again', 'بررسی مجدد اجرا')}</button><button className="text-button" onClick={() => { setRunId(''); try { sessionStorage.removeItem('balance-lab-run'); } catch { /* Optional persistence. */ } }}>{t('Dismiss saved report', 'بستن گزارش ذخیره‌شده')}</button></p>}
    {r && <section className="panel"><div className="panel-heading"><div><h2>{r.state === 'RUNNING' ? t('Executing on the server…', 'در حال اجرا در سرور…') : t('Experiment results', 'نتایج آزمایش')}</h2><p><bdi>{r.id} · {timestamp(r.startedAt)}</bdi></p><p>{t('Reported state', 'وضعیت گزارش‌شده')}: <bdi>{r.state}</bdi>{result.isError && ' · ' + t('STALE', 'قدیمی')}</p></div></div>
      <div className="run-stats">{[[t('Requests', 'درخواست‌ها'), r.requested], [t('Acknowledged', 'تأییدشده'), r.acknowledged], [t('Rejected', 'ردشده'), r.rejected], [t('Technical errors', 'خطاهای فنی'), r.errors], [t('Unique commits', 'ثبت‌های یکتا'), r.committedReceipts], [t('Service-call p95', 'صدک ۹۵ فراخوانی سرویس'), r.p95Ms.toFixed(1) + ' ms']].map(([label, value]) => <div key={label}><small>{label}</small><strong><bdi>{r.state === 'RUNNING' ? '—' : value}</bdi></strong></div>)}</div>
      {r.state === 'COMPLETED' && <><div className="checks">{[[t('Total conserved', 'حفظ مجموع'), r.conserved], [t('No overdrafts', 'عدم موجودی منفی'), r.nonnegative], [t('Expected effect', 'اثر مورد انتظار'), r.expectedEffect]].map(([label, pass]) => <span key={String(label)} className={pass ? 'pass' : 'fail'}>{pass ? '✓' : '✗'} {label} · {pass ? t('Passed', 'موفق') : t('Failed', 'ناموفق')}</span>)}</div>
        <div className="balance-results">{Object.entries(r.balances).map(([id, balance]) => <div key={id}><bdi>{id}</bdi><strong><bdi>{units(balance)}</bdi></strong></div>)}</div>
        <p className="hint padded"><bdi>{r.message}</bdi> · {r.durationMs.toFixed(0)} ms · {t('p95 measures service-call duration including lock waits, not HTTP or Kafka latency.', 'صدک ۹۵ مدت فراخوانی سرویس با انتظار قفل است، نه تأخیر HTTP یا Kafka.')}</p>
        <details><summary>{t('Inspect request outcomes', 'بررسی نتایج درخواست‌ها')} ({r.attempts.length})</summary><div className="table-scroll"><table><thead><tr><th>#</th><th>{t('Transaction ID', 'شناسه تراکنش')}</th><th>{t('Outcome', 'نتیجه')}</th><th>{t('Service-call duration', 'مدت فراخوانی سرویس')}</th></tr></thead><tbody>{r.attempts.map(attempt => <tr key={attempt.index}><td>{attempt.index + 1}</td><td><bdi>{attempt.transactionId}</bdi></td><td><bdi>{attempt.outcome}</bdi></td><td dir="ltr">{attempt.durationMs.toFixed(2)} ms</td></tr>)}</tbody></table></div></details></>}
      {r.state === 'FAILED' && <p className="notice error">{r.message}</p>}
    </section>}
  </>;
}
