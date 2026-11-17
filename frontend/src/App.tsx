import { useEffect, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { RefreshCw, X } from 'lucide-react';
import { api, observeApi, timestamp, units, type ApiEntry, type Receipt, type Snapshot } from './api';
import { LanguageContext, useText, type Language } from './i18n';
import { CreateAccount, OperationForm } from './Operations';
import { Monitoring, RequestPath, Structure } from './Education';
import Lab from './Lab';

type Page = 'overview' | 'accounts' | 'transfers' | 'path' | 'structure' | 'monitoring' | 'lab' | 'api';
const pageIds: Page[] = ['overview', 'accounts', 'transfers', 'path', 'structure', 'monitoring', 'lab', 'api'];
function initialPage(): Page { const value = location.hash.slice(1) as Page; return pageIds.includes(value) ? value : 'overview'; }
function preference(key: string, fallback: string) { try { return localStorage.getItem(key) || fallback; } catch { return fallback; } }
function save(key: string, value: string) { try { localStorage.setItem(key, value); } catch { /* Preferences are optional. */ } }
function Panel({ title, subtitle, action, children }: { title: string; subtitle?: string; action?: ReactNode; children: ReactNode }) {
  return <section className="panel"><div className="panel-heading"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{action}</div>{children}</section>;
}
function Pager({ offset, total, set }: { offset: number; total: number; set: (offset: number) => void }) {
  const t = useText();
  return <div className="pager"><span dir="ltr">{total ? offset + 1 : 0}–{Math.min(offset + 20, total)} / {total}</span><button aria-label={t('Previous page', 'صفحه قبل')} disabled={!offset} onClick={() => set(Math.max(0, offset - 20))}>‹</button><button aria-label={t('Next page', 'صفحه بعد')} disabled={offset + 20 >= total} onClick={() => set(offset + 20)}>›</button></div>;
}
function Receipts({ receipts, select, available }: { receipts: Receipt[]; select: (receipt: Receipt) => void; available: boolean }) {
  const t = useText();
  if (!available) return <p className="empty">{t('Receipt data unavailable.', 'داده رسیدها در دسترس نیست.')}</p>;
  if (!receipts.length) return <p className="empty">{t('No successful receipts in this selection.', 'در این انتخاب، رسید موفقی وجود ندارد.')}</p>;
  return <div className="table-scroll" tabIndex={0} role="region" aria-label={t('Successful receipts', 'رسیدهای موفق')}><table><thead><tr>{[t('Transaction / time', 'تراکنش و زمان'), t('Route', 'مسیر'), t('Amount', 'مبلغ'), t('Type', 'نوع'), t('Status', 'وضعیت')].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{receipts.map(receipt => <tr key={receipt.transactionId}><td><button className="table-link" onClick={() => select(receipt)}><bdi>{receipt.transactionId}</bdi></button><small dir="ltr">{timestamp(receipt.processedAt)}</small></td><td><bdi>{receipt.source}{receipt.destination && ' → ' + receipt.destination}</bdi></td><td className="numeric"><bdi>{units(receipt.amount)}</bdi></td><td><bdi>{receipt.type}</bdi></td><td className="status">✓ {t('Committed', 'ثبت‌شده')}</td></tr>)}</tbody></table></div>;
}
function Workspace({ language, setLanguage }: { language: Language; setLanguage: (value: Language) => void }) {
  const t = useText(), client = useQueryClient();
  const [page, setPage] = useState<Page>(initialPage), [auto, setAuto] = useState(true);
  const [theme, setTheme] = useState(() => preference('balance-theme', 'light'));
  const [dense, setDense] = useState(() => preference('balance-density', 'comfortable') === 'compact');
  const [explain, setExplain] = useState(true);
  const [accountOffset, setAccountOffset] = useState(0), [receiptOffset, setReceiptOffset] = useState(0);
  const [filter, setFilter] = useState(''), [filterDraft, setFilterDraft] = useState('');
  const [creating, setCreating] = useState(false), [selected, setSelected] = useState<Receipt | ApiEntry | null>(null);
  const [logs, setLogs] = useState<ApiEntry[]>([]);
  const [samples, setSamples] = useState<{ time: number; total: number; rate: number }[]>([]);
  const [now, setNow] = useState(Date.now());
  const activeFilter = page === 'transfers' ? filter : '';
  const snapshot = useQuery({ queryKey: ['snapshot', page === 'accounts' ? accountOffset : 0, page === 'transfers' ? receiptOffset : 0, activeFilter],
    queryFn: () => api<Snapshot>('/api/console/snapshot?' + new URLSearchParams({ accountOffset: String(page === 'accounts' ? accountOffset : 0), receiptOffset: String(page === 'transfers' ? receiptOffset : 0), account: activeFilter }), undefined, false), refetchInterval: auto ? 2000 : false });
  const s = snapshot.data;
  const stale = snapshot.isError || !!s && now - Date.parse(s.capturedAt) > 10000;
  const refresh = () => { void client.invalidateQueries({ queryKey: ['snapshot'] }); };
  const go = (value: Page) => { location.hash = value; setPage(value); };
  const labels: Record<Page, string> = { overview: t('Overview', 'نمای کلی'), accounts: t('Accounts', 'حساب‌ها'), transfers: t('Transactions', 'تراکنش‌ها'), path: t('Request path', 'مسیر درخواست'), structure: t('Structure', 'ساختار و ارتباط‌ها'), monitoring: t('Technical monitoring', 'پایش فنی'), lab: t('Concurrency lab', 'آزمایشگاه هم‌زمانی'), api: t('API inspector', 'بازرس API') };
  useEffect(() => { document.documentElement.dataset.theme = theme; save('balance-theme', theme); }, [theme]);
  useEffect(() => { save('balance-density', dense ? 'compact' : 'comfortable'); }, [dense]);
  useEffect(() => { const handle = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(handle); }, []);
  useEffect(() => { const onHash = () => setPage(initialPage()); window.addEventListener('hashchange', onHash); return () => window.removeEventListener('hashchange', onHash); }, []);
  useEffect(() => observeApi(entry => setLogs(previous => [entry, ...previous].slice(0, 100))), []);
  useEffect(() => {
    if (!s) return;
    setSamples(previous => {
      const time = Date.parse(s.capturedAt), last = previous.at(-1);
      if (last && time <= last.time) return previous;
      // Long sampling gaps and counter resets do not produce fabricated rates.
      const rate = last && time - last.time <= 10000 && s.receiptCount >= last.total ? (s.receiptCount - last.total) / ((time - last.time) / 1000) : 0;
      return [...previous, { time, total: s.receiptCount, rate }].slice(-30);
    });
  }, [s?.capturedAt]);
  useEffect(() => {
    if (!creating && !selected) return;
    const previousFocus = document.activeElement as HTMLElement | null;
    document.querySelector<HTMLElement>('.drawer button')?.focus();
    const listener = (event: KeyboardEvent) => { if (event.key === 'Escape') { setCreating(false); setSelected(null); } };
    window.addEventListener('keydown', listener);
    return () => { window.removeEventListener('keydown', listener); previousFocus?.focus(); };
  }, [creating, selected]);
  const rates = samples.slice(1), maxRate = Math.max(1, ...rates.map(sample => sample.rate));
  const close = () => { setCreating(false); setSelected(null); };
  return <div className={'app ' + (dense ? 'compact' : '')}>
    <div className="app-background" inert={creating || !!selected}>
    <a className="skip-link" href="#main-content">{t('Skip to content', 'رفتن به محتوا')}</a>
    <aside className="sidebar"><a className="brand" href="#overview" dir="ltr">Balance Lab</a><span className="nav-caption">{t('WORKSPACE', 'فضای کار')}</span><nav aria-label={t('Main navigation', 'ناوبری اصلی')}>{pageIds.map(id => <a key={id} href={'#' + id} className={page === id ? 'active' : ''} aria-current={page === id ? 'page' : undefined}>{labels[id]}</a>)}</nav><div className="sidebar-bottom"><p>PostgreSQL · Redis · Kafka</p><a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">{t('API documentation', 'مستندات API')} ↗</a></div></aside>
    <div className="workspace"><header className="topbar"><strong>{labels[page]}</strong><span className={'connection ' + (stale ? 'offline' : s ? '' : 'waiting')}><i />{stale ? t('Stale / connection lost', 'قدیمی / ارتباط قطع شده') : s ? t('Service connected', 'سرویس متصل است') : t('Connecting…', 'در حال اتصال…')}</span></header>
    <main id="main-content" tabIndex={-1}><div className="page-heading"><div><span className="eyebrow" dir="ltr">BALANCE LAB</span><h1>{labels[page]}</h1><p>{t('Explore balances and the evidence behind them.', 'موجودی‌ها و شواهد پشت آن‌ها را بررسی کنید.')}</p></div><div className="heading-actions"><button className="secondary" onClick={refresh} aria-label={t('Refresh data', 'تازه‌سازی داده‌ها')}><RefreshCw size={16} className={snapshot.isFetching ? 'spin' : ''} /></button><button className="primary" onClick={() => page === 'accounts' ? setCreating(true) : go('transfers')}>{page === 'accounts' ? t('New account', 'حساب جدید') : t('New transaction', 'تراکنش جدید')}</button></div></div>
    <section className="workspace-controls" aria-label={t('Display preferences', 'تنظیمات نمایش')}>
      <label>{t('Language', 'زبان')}<select value={language} onChange={event => setLanguage(event.target.value as Language)}><option value="fa">فارسی</option><option value="en">English</option></select></label>
      <label>{t('Theme', 'پوسته')}<select value={theme} onChange={event => setTheme(event.target.value)}><option value="light">{t('Light', 'روشن')}</option><option value="dark">{t('Dark', 'تیره')}</option></select></label>
      <label className="toggle"><input type="checkbox" checked={dense} onChange={event => setDense(event.target.checked)} />{t('Compact display', 'نمایش فشرده')}</label>
      <label className="toggle"><input type="checkbox" checked={explain} onChange={event => setExplain(event.target.checked)} />{t('Educational explanations', 'توضیحات آموزشی')}</label>
    </section>
    <p className={'snapshot-meta ' + (stale ? 'error-text' : '')}>{t('Environment: connected backend (same origin)', 'محیط: سرور متصل (هم‌مبدأ)')} · <bdi>{location.origin}</bdi> · {t('LIVE data; lab creates real persistent demo accounts', 'داده زنده؛ آزمایشگاه حساب‌های نمونه واقعی و پایدار ایجاد می‌کند')} · {t('Source: PostgreSQL snapshot', 'منبع: تصویر PostgreSQL')} · <bdi>{s ? timestamp(s.capturedAt) : t('No snapshot', 'بدون تصویر')}</bdi> · {stale ? t('STALE', 'قدیمی') : !auto ? t('Polling paused', 'به‌روزرسانی متوقف') : t('Polling every 2s', 'به‌روزرسانی هر ۲ ثانیه')}</p>
    {explain && <p className="notice explanation">{t('Receipts prove successful effects, not every HTTP attempt or Kafka delivery. Balances and amounts use exact decimal units with no assumed currency. All timestamps are displayed in UTC.', 'رسیدها اثر موفق را اثبات می‌کنند، نه تمام تلاش‌های HTTP یا تحویل Kafka را. مبالغ و موجودی‌ها اعشاری دقیق و بدون فرض ارز هستند. همه زمان‌ها با منطقه UTC نمایش داده می‌شوند.')}</p>}
    {snapshot.isError && <p className="notice error" role="alert">{(snapshot.error as Error).message} {s && t('Showing the last successful snapshot.', 'آخرین تصویر موفق نمایش داده می‌شود.')}<button className="text-button" onClick={refresh}>{t('Retry', 'تلاش مجدد')}</button></p>}
    {!s && snapshot.isPending && <p className="loading" role="status">{t('Loading committed data…', 'در حال دریافت داده ثبت‌شده…')}</p>}
    {(page === 'overview' || page === 'accounts') && <div className="metrics">{[
      [t('Total balance', 'مجموع موجودی'), s ? units(s.totalBalance) : '—', t('exact units · one snapshot', 'واحد دقیق · یک تصویر')],
      [t('Accounts', 'حساب‌ها'), s?.accountCount.toLocaleString() ?? '—', t('persistent accounts', 'حساب‌های پایدار')],
      [t('Committed operations', 'عملیات ثبت‌شده'), s?.receiptCount.toLocaleString() ?? '—', t('unique transaction IDs', 'شناسه‌های یکتای تراکنش')],
      [t('Outbox backlog', 'صف رویدادهای معلق'), s?.pendingEvents.toLocaleString() ?? '—', t('pending, not delivery evidence', 'معلق؛ اثبات تحویل نیست')],
    ].map(([label, value, suffix]) => <div className="metric" key={label}><div>{label}</div><strong dir="ltr">{value}</strong><small>{suffix}</small></div>)}</div>}
    {page === 'overview' && <>
      <Panel title={t('Committed activity', 'فعالیت ثبت‌شده')} subtitle={t('Observed receipt-count changes in this tab; not a load-test throughput measure.', 'تغییر شمار رسیدها در این برگه؛ اندازه‌گیری توان عملیاتی آزمون بار نیست.')} action={<span className="tag">{stale ? t('STALE', 'قدیمی') : auto ? t('LIVE', 'زنده') : t('PAUSED', 'متوقف')}</span>}>
        <div className="activity-chart" aria-hidden="true"><div className="bars">{rates.map(sample => <div className="bar-slot" key={sample.time}><div style={{ height: Math.max(0, sample.rate / maxRate * 100) + '%' }} /></div>)}{!rates.length && <span className="chart-empty">{t('Collecting observations…', 'در حال گردآوری مشاهدات…')}</span>}</div></div>
        <details><summary>{t('Activity data · UTC', 'داده فعالیت · UTC')}</summary><div className="table-scroll"><table><thead><tr><th>{t('Time', 'زمان')}</th><th>{t('Observed commits/s', 'ثبت مشاهده‌شده در ثانیه')}</th></tr></thead><tbody>{rates.map(sample => <tr key={sample.time}><td dir="ltr">{timestamp(new Date(sample.time).toISOString())}</td><td>{sample.rate.toFixed(2)}</td></tr>)}</tbody></table></div></details>
      </Panel>
      <Monitoring snapshot={s} stale={stale} />
      <Panel title={t('Recent transactions', 'تراکنش‌های اخیر')}><Receipts available={!!s} receipts={s?.receipts.slice(0, 6) || []} select={setSelected} /></Panel>
    </>}
    {page === 'accounts' && <Panel title={t('Account directory', 'فهرست حساب‌ها')} subtitle={t('20 accounts per page. Pages can change between snapshots.', '۲۰ حساب در هر صفحه؛ صفحات ممکن است بین تصاویر تغییر کنند.')}>
      {s?.accounts.length ? <div className="table-scroll" tabIndex={0} role="region" aria-label={labels.accounts}><table><thead><tr><th>{t('Account ID', 'شناسه حساب')}</th><th>{t('Available balance', 'موجودی در دسترس')}</th><th>{t('History', 'تاریخچه')}</th></tr></thead><tbody>{s.accounts.map(account => <tr key={account.id}><td><bdi>{account.id}</bdi></td><td className="numeric"><bdi>{units(account.balance)}</bdi></td><td><button className="text-button" onClick={() => { setFilter(account.id); setFilterDraft(account.id); setReceiptOffset(0); go('transfers'); }}>{t('View history', 'مشاهده تاریخچه')}</button></td></tr>)}</tbody></table></div> : <p className="empty">{s ? t('No accounts on this page.', 'حسابی در این صفحه وجود ندارد.') : t('Account data unavailable.', 'داده حساب‌ها در دسترس نیست.')}</p>}
      <Pager offset={accountOffset} total={s?.accountCount || 0} set={setAccountOffset} /></Panel>}
    {page === 'transfers' && <><Panel title={t('Move a balance', 'جابجایی موجودی')}><OperationForm refresh={refresh} accounts={s?.accounts || []} initialAccount={filter} /></Panel><Panel title={t('Committed history', 'تاریخچه ثبت‌شده')} subtitle={t('Successful receipts only. Rejections and retries in this tab appear in the API inspector.', 'فقط رسیدهای موفق؛ رد شدن‌ها و تلاش‌های مجدد این برگه در بازرس API هستند.')} action={<form className="filter" onSubmit={event => { event.preventDefault(); setFilter(filterDraft); setReceiptOffset(0); }}><input dir="ltr" aria-label={t('Filter history by exact account ID', 'فیلتر تاریخچه با شناسه دقیق حساب')} maxLength={255} value={filterDraft} onChange={event => setFilterDraft(event.target.value)} /><button className="secondary">{t('Filter', 'فیلتر')}</button>{filter && <button type="button" className="text-button" onClick={() => { setFilter(''); setFilterDraft(''); setReceiptOffset(0); }}>{t('Clear', 'پاک کردن')}</button>}</form>}><Receipts available={!!s} receipts={s?.receipts || []} select={setSelected} /><Pager offset={receiptOffset} total={s?.filteredReceiptCount || 0} set={setReceiptOffset} /></Panel></>}
    {page === 'path' && <RequestPath />}
    {page === 'structure' && <Structure />}
    {page === 'monitoring' && <Monitoring snapshot={s} stale={stale} />}
    {page === 'lab' && <><p className="notice explanation">{t('Shared-stack demonstration, not the isolated fault-test runner proposed in the plan. Uses unique accounts in the current database. No cancellation, infrastructure fault controls, durable reports, or reproducible revision/seed metadata are available.', 'نمایش در محیط مشترک؛ اجراکننده مجزای آزمون خطای طرح نیست. حساب‌های یکتا در پایگاه داده فعلی می‌سازد. لغو اجرا، کنترل خطای زیرساخت، گزارش پایدار و اطلاعات نسخه و بذر بازتولید موجود نیست.')}</p><Lab enabled={s?.labEnabled || false} refresh={refresh} /></>}
    {page === 'api' && <Panel title={t('Request inspector', 'بازرس درخواست')} subtitle={t('Last 100 explicit actions in this tab; not a durable attempt log. IDs identify local observations, not server trace IDs. Polling is omitted.', '۱۰۰ اقدام صریح اخیر در این برگه؛ گزارش پایدار تلاش‌ها نیست. شناسه‌ها برای مشاهدات محلی هستند، نه ردگیری سرور. به‌روزرسانی دوره‌ای ثبت نمی‌شود.')} action={<button className="text-button" onClick={() => setLogs([])}>{t('Clear log', 'پاک کردن گزارش')}</button>}>
      {logs.length ? <div className="table-scroll" tabIndex={0} role="region" aria-label={labels.api}><table><thead><tr>{['HTTP / UTC', t('Endpoint', 'مسیر'), t('Status', 'وضعیت'), t('Browser round trip', 'رفت‌وبرگشت مرورگر'), t('Details', 'جزئیات')].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{logs.map(log => <tr key={log.id}><td><bdi>{log.method}</bdi><small dir="ltr">{timestamp(log.time)}</small></td><td><bdi>{log.path}</bdi></td><td>{log.status || t('Unknown / network error', 'نامشخص / خطای ارتباط')}</td><td dir="ltr">{log.duration.toFixed(0)} ms</td><td><button className="text-button" onClick={() => setSelected(log)}>{t('Inspect', 'بررسی')}</button></td></tr>)}</tbody></table></div> : <p className="empty">{t('No explicit requests observed in this tab yet.', 'هنوز درخواست صریحی در این برگه مشاهده نشده است.')}</p>}
    </Panel>}
    <footer><span>{t('Display timezone: UTC', 'منطقه زمانی نمایش: UTC')}</span><label className="toggle"><input type="checkbox" checked={auto} onChange={event => setAuto(event.target.checked)} />{t('Auto-refresh every 2s', 'تازه‌سازی خودکار هر ۲ ثانیه')}</label></footer></main></div></div>
    {(creating || selected) && <div className="overlay" onClick={close}><section className="drawer" role="dialog" aria-modal="true" aria-label={creating ? t('Create account', 'ایجاد حساب') : t('Inspect details', 'بررسی جزئیات')} onClick={event => event.stopPropagation()} onKeyDown={event => {
      if (event.key !== 'Tab') return;
      const elements = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('button, input, a[href], summary, select')).filter(element => !element.matches(':disabled'));
      const first = elements[0], last = elements.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }}><div className="drawer-heading"><h2>{creating ? t('Create account', 'ایجاد حساب') : selected && 'method' in selected ? t('API request details', 'جزئیات درخواست API') : t('Committed transaction', 'تراکنش ثبت‌شده')}</h2><button className="icon-button" aria-label={t('Close details', 'بستن جزئیات')} onClick={close}><X size={20} /></button></div>
      {creating ? <CreateAccount done={() => { setCreating(false); refresh(); }} /> : selected && 'method' in selected ? <div className="inspection"><h3><bdi>{selected.method} {selected.path}</bdi></h3><p><bdi>{selected.status || 'UNKNOWN'} · {selected.duration.toFixed(2)} ms</bdi> · {t('browser round trip', 'رفت‌وبرگشت مرورگر')}</p><p>{t('Local observation ID', 'شناسه مشاهده محلی')}: <bdi>{selected.id}</bdi></p><h3>{t('Request body', 'بدنه درخواست')}</h3><pre dir="ltr">{selected.request || '(no body)'}</pre><h3>{t('Response body', 'بدنه پاسخ')}</h3><pre dir="ltr">{selected.response || '(empty response)'}</pre><button className="text-button" onClick={() => { close(); go('path'); }}>{t('Conceptual request path', 'مسیر مفهومی درخواست')}</button></div> : <div className="inspection"><p>✓ {t('Committed', 'ثبت‌شده')}</p><pre dir="ltr">{JSON.stringify(selected, null, 2)}</pre><p>{t('This receipt proves a committed effect. processedAt is constructed before commit; it is not the exact commit time. Kafka delivery is unknown.', 'این رسید اثر ثبت‌شده را اثبات می‌کند. processedAt پیش از ثبت ساخته شده و زمان دقیق ثبت نیست. تحویل Kafka نامشخص است.')}</p><button className="text-button" onClick={() => { close(); go('path'); }}>{t('Conceptual request path', 'مسیر مفهومی درخواست')}</button></div>}
    </section></div>}
  </div>;
}
export default function App() {
  const [language, setLanguage] = useState<Language>(() => preference('balance-language', 'fa') === 'en' ? 'en' : 'fa');
  useEffect(() => { document.documentElement.lang = language; document.documentElement.dir = language === 'fa' ? 'rtl' : 'ltr'; save('balance-language', language); }, [language]);
  return <LanguageContext.Provider value={language}><Workspace language={language} setLanguage={setLanguage} /></LanguageContext.Provider>;
}

