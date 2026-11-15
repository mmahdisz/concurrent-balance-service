import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, ArrowDownLeft, ArrowRight, ArrowUpRight, Check, CheckCircle2, ChevronLeft, ChevronRight, Circle, Code2, Database, FlaskConical, Layers3, LayoutDashboard, Plus, RefreshCw, Send, Wallet, X, Zap } from 'lucide-react';
import { api, ApiError, observeApi, units, type ApiEntry, type Receipt, type Run, type Scenario, type Snapshot } from './api';

type Page = 'overview' | 'accounts' | 'transfers' | 'lab' | 'api';
const pages: { id: Page; title: string; icon: typeof Activity }[] = [
  { id: 'overview', title: 'Overview', icon: LayoutDashboard }, { id: 'accounts', title: 'Accounts', icon: Wallet },
  { id: 'transfers', title: 'Transactions', icon: ArrowRight }, { id: 'lab', title: 'Concurrency lab', icon: FlaskConical },
  { id: 'api', title: 'API inspector', icon: Code2 },
];
const scenarios: { id: Scenario; title: string; description: string; expected: string }[] = [
  { id: 'COMPETING', title: 'Race for the balance', description: 'Two simultaneous transfers of 80 compete for a balance of 100.', expected: 'One commit. One insufficient-funds rejection. No overdraft.' },
  { id: 'DUPLICATE', title: 'One ID, many requests', description: 'Submit the same transfer repeatedly with one transaction ID.', expected: 'Many acknowledgements. Exactly one committed effect of 10 units.' },
  { id: 'OPPOSING', title: 'Opposite directions', description: 'Send A → B and B → A concurrently, one unit at a time.', expected: 'Ordered locking keeps opposing transfers moving. Total stays 2,000.' },
  { id: 'BURST', title: 'Contention burst', description: 'Queue a configurable burst of transfers against the same two accounts.', expected: 'Inspect server latency and unique commits under bounded contention.' },
];
function initialPage(): Page {
  const p = location.hash.slice(1);
  return pages.some(x => x.id === p) ? p as Page : 'overview';
}
function Panel({ title, subtitle, action, children, className = '' }: { title: string; subtitle?: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return <section className={'panel ' + className}><div className="panel-heading"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{action}</div>{children}</section>;
}
function Pager({ offset, total, set }: { offset: number; total: number; set: (n: number) => void }) {
  return <div className="pager"><span>{total ? offset + 1 : 0}–{Math.min(offset + 20, total)} of {total}</span><button aria-label="Previous page" disabled={!offset} onClick={() => set(Math.max(0, offset - 20))}><ChevronLeft size={16} /></button><button aria-label="Next page" disabled={offset + 20 >= total} onClick={() => set(offset + 20)}><ChevronRight size={16} /></button></div>;
}
function Receipts({ receipts, select }: { receipts: Receipt[]; select: (r: Receipt) => void }) {
  return receipts.length ? <div className="table-scroll"><table><thead><tr><th>Transaction / time</th><th>Route</th><th>Amount</th><th>Type</th><th>Status</th></tr></thead><tbody>{receipts.map(r => <tr key={r.transactionId} onClick={() => select(r)}><td><button className="table-link" onClick={e => { e.stopPropagation(); select(r); }}>{r.transactionId}</button><small>{new Date(r.processedAt).toLocaleString()}</small></td><td><span className="route">{r.source} {r.destination && <>→ {r.destination}</>}</span></td><td className="numeric">{units(r.amount)} <small className="inline">units</small></td><td><span className="tag">{r.type.toLowerCase()}</span></td><td><span className="status"><CheckCircle2 size={13} /> Committed</span></td></tr>)}</tbody></table></div> : <div className="empty"><Layers3 size={28} /><h3>No committed transactions yet</h3><p>Create accounts and submit your first operation.</p></div>;
}
function OperationForm({ refresh, accounts }: { refresh: () => void; accounts: Snapshot['accounts'] }) {
  const [operation, setOperation] = useState('transfer');
  const [source, setSource] = useState(''), [destination, setDestination] = useState('');
  const [amount, setAmount] = useState('100'), [tx, setTx] = useState<string>(crypto.randomUUID());
  const [busy, setBusy] = useState(false), [message, setMessage] = useState(''), [failed, setFailed] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setMessage('');
    try {
      await api('/accounts/' + encodeURIComponent(source) + '/' + operation, { amount, transactionId: tx, ...(operation === 'transfer' ? { destinationAccountId: destination } : {}) });
      setFailed(false); setMessage('Acknowledged. Balances refreshed from the server. The same ID safely replays this operation.'); refresh();
    } catch (e) { setFailed(true); setMessage((e as Error).message); }
    finally { setBusy(false); }
  }
  return <form onSubmit={submit} className="operation-form"><div className="segmented">{['transfer', 'credit', 'debit'].map(o => <button key={o} type="button" className={operation === o ? 'selected' : ''} onClick={() => { setOperation(o); setMessage(''); }}>{o}</button>)}</div>
    <datalist id="account-options">{accounts.map(a => <option value={a.id} key={a.id} />)}</datalist>
    <div className="form-grid"><label>{operation === 'transfer' ? 'From account' : 'Account ID'}<input required maxLength={255} list="account-options" value={source} onChange={e => setSource(e.target.value)} placeholder="e.g. treasury" /></label>
    {operation === 'transfer' && <label>To account<input required maxLength={255} list="account-options" value={destination} onChange={e => setDestination(e.target.value)} placeholder="e.g. operations" /></label>}
    <label>Amount · whole units<input required inputMode="numeric" pattern="[0-9]+" value={amount} onChange={e => setAmount(e.target.value)} /></label>
    <label className="wide">Transaction ID<div className="input-action"><input required maxLength={255} value={tx} onChange={e => setTx(e.target.value)} /><button type="button" title="Generate a new transaction ID" onClick={() => { setTx(crypto.randomUUID()); setMessage(''); }}><RefreshCw size={15} /></button></div></label></div>
    <p className="hint">Reuse this ID to retry the same operation. Generate a new ID for a new transaction.</p>
    <button className="primary" disabled={busy || (operation === 'transfer' && !!source && source === destination)}><Send size={16} />{busy ? 'Submitting…' : 'Submit ' + operation}</button>
    {message && <p role="status" className={'notice ' + (failed ? 'error' : 'success')}>{message}</p>}
  </form>;
}
function CreateAccount({ done }: { done: () => void }) {
  const [id, setId] = useState(''), [balance, setBalance] = useState('1000'), [busy, setBusy] = useState(false), [error, setError] = useState('');
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError('');
    try { await api('/accounts', { accountId: id, initialBalance: balance }); done(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <form onSubmit={submit} className="operation-form"><label>Account ID<input required maxLength={255} value={id} onChange={e => setId(e.target.value)} placeholder="e.g. treasury" /></label><label>Initial balance · whole units<input required pattern="[0-9]+" inputMode="numeric" value={balance} onChange={e => setBalance(e.target.value)} /></label><p className="hint">Balances are exact integer units. No currency conversion or decimal rounding.</p><button disabled={busy} className="primary"><Plus size={16} />{busy ? 'Creating…' : 'Create account'}</button>{error && <p className="notice error" role="alert">{error}</p>}</form>;
}
function Lab({ enabled, refresh }: { enabled: boolean; refresh: () => void }) {
  const [scenario, setScenario] = useState<Scenario>('COMPETING'), [requests, setRequests] = useState(20), [concurrency, setConcurrency] = useState(4);
  const [runId, setRunId] = useState(() => sessionStorage.getItem('balance-lab-run') || '');
  const [starting, setStarting] = useState(false), [error, setError] = useState('');
  const result = useQuery({ queryKey: ['run', runId], queryFn: () => api<Run>('/api/demo/runs/' + runId, undefined, false), enabled: !!runId,
    refetchInterval: q => q.state.data?.state === 'RUNNING' ? 700 : false });
  const r = result.data;
  useEffect(() => { if (r?.state === 'COMPLETED' || r?.state === 'FAILED') refresh(); }, [r?.state]);
  async function start() {
    setStarting(true); setError('');
    try {
      const run = await api<Run>('/api/demo/runs', { scenario, requests, concurrency });
      sessionStorage.setItem('balance-lab-run', run.id); setRunId(run.id);
    } catch (e) { setError((e as Error).message); } finally { setStarting(false); }
  }
  return <><div className="section-intro"><span className="eyebrow">THE ENGINEERING PLAYGROUND</span><h2>Put correctness under pressure.</h2><p>Real concurrent operations. Isolated accounts. Verifiable results.</p></div>
    {!enabled && <p className="notice error">The lab is disabled. Start the backend with BALANCE_DEMO_ENABLED=true, or use Docker Compose.</p>}
    <div className="scenario-grid">{scenarios.map((s, i) => <button key={s.id} className={'scenario ' + (scenario === s.id ? 'active' : '')} onClick={() => setScenario(s.id)}><span className="scenario-number">0{i + 1}</span><h3>{s.title}</h3><p>{s.description}</p><span className="scenario-check">{scenario === s.id ? <CheckCircle2 size={18} /> : <Circle size={18} />}</span></button>)}</div>
    <Panel title="Configure your experiment" subtitle={scenarios.find(s => s.id === scenario)?.expected}><div className="lab-controls"><label>Requests<input type="number" min={2} max={100} value={scenario === 'COMPETING' ? 2 : requests} disabled={scenario === 'COMPETING'} onChange={e => setRequests(Number(e.target.value))} /></label><label>Concurrent workers<input type="number" min={2} max={16} value={concurrency} onChange={e => setConcurrency(Number(e.target.value))} /></label><button className="primary" onClick={start} disabled={!enabled || starting || r?.state === 'RUNNING' || requests < 2 || requests > 100 || concurrency < 2 || concurrency > 16 || !Number.isInteger(requests) || !Number.isInteger(concurrency)}><Zap size={16} />{starting || r?.state === 'RUNNING' ? 'Experiment running…' : 'Run experiment'}</button></div><p className="hint padded">One run at a time per service instance, up to 100 requests and 16 workers. Demo accounts and committed receipts persist; run reports are temporary.</p></Panel>
    {(error || result.error) && <p className="notice error" role="alert">{error || (result.error as Error).message}<button className="text-button" onClick={() => result.refetch()}>Check run again</button></p>}
    {r && <Panel title={r.state === 'RUNNING' ? 'Executing on the server…' : 'Experiment results'} subtitle={'Run ' + r.id}><div className="run-stats">{[['Requests', r.requested], ['Acknowledged', r.acknowledged], ['Rejected', r.rejected], ['Technical errors', r.errors], ['Unique commits', r.committedReceipts], ['Server p95', r.p95Ms.toFixed(1) + ' ms']].map(([label,value]) => <div key={label}><small>{label}</small><strong>{r.state === 'RUNNING' ? '—' : value}</strong></div>)}</div>
    {r.state === 'COMPLETED' && <><div className="checks">{[['Total conserved', r.conserved], ['No overdrafts', r.nonnegative], ['Expected effect', r.expectedEffect]].map(([label,pass]) => <span key={String(label)} className={pass ? 'pass' : 'fail'}>{pass ? <CheckCircle2 size={16} /> : <X size={16} />}{label}</span>)}</div><div className="balance-results">{Object.entries(r.balances).map(([id,balance]) => <div key={id}><span>{id}</span><strong>{units(balance)} units</strong></div>)}</div><p className="hint padded">{r.message} Total elapsed: {r.durationMs.toFixed(0)} ms. p95 measures service-call duration, including lock waits.</p><details className="attempts"><summary>Inspect {r.attempts.length} request outcomes</summary><div className="table-scroll"><table><thead><tr><th>#</th><th>Transaction ID</th><th>Outcome</th><th>Server duration</th></tr></thead><tbody>{r.attempts.map(a => <tr key={a.index}><td>{a.index + 1}</td><td className="mono">{a.transactionId}</td><td>{a.outcome}</td><td>{a.durationMs.toFixed(2)} ms</td></tr>)}</tbody></table></div></details></>}
    {r.state === 'FAILED' && <p className="notice error">{r.message}</p>}</Panel>}
  </>;
}
export default function App() {
  const client = useQueryClient();
  const [page, setPage] = useState<Page>(initialPage), [auto, setAuto] = useState(true);
  const [accountOffset, setAccountOffset] = useState(0), [receiptOffset, setReceiptOffset] = useState(0);
  const [filter, setFilter] = useState(''), [filterDraft, setFilterDraft] = useState('');
  const [creating, setCreating] = useState(false), [selected, setSelected] = useState<Receipt | ApiEntry | null>(null);
  const [logs, setLogs] = useState<ApiEntry[]>([]), [samples, setSamples] = useState<{time: number; total: number; rate: number}[]>([]);
  const activeFilter = page === 'transfers' ? filter : '';
  const activeAccountOffset = page === 'accounts' ? accountOffset : 0;
  const activeReceiptOffset = page === 'transfers' ? receiptOffset : 0;
  const snapshot = useQuery({ queryKey: ['snapshot', activeAccountOffset, activeReceiptOffset, activeFilter], queryFn: () => api<Snapshot>('/api/console/snapshot?' + new URLSearchParams({ accountOffset: String(activeAccountOffset), receiptOffset: String(activeReceiptOffset), account: activeFilter }), undefined, false), refetchInterval: auto ? 2000 : false });
  const s = snapshot.data;
  const refresh = () => { void client.invalidateQueries({ queryKey: ['snapshot'] }); };
  const go = (id: Page) => { location.hash = id; setPage(id); };
  useEffect(() => { const onHash = () => setPage(initialPage()); window.addEventListener('hashchange', onHash); return () => window.removeEventListener('hashchange', onHash); }, []);
  useEffect(() => observeApi(entry => setLogs(previous => [entry, ...previous].slice(0, 100))), []);
  useEffect(() => {
    if (!s) return;
    setSamples(prev => {
      const time = Date.parse(s.capturedAt), last = prev.at(-1);
      if (last && time <= last.time) return prev;
      const rate = last ? Math.max(0, s.receiptCount - last.total) / ((time - last.time) / 1000) : 0;
      return [...prev, { time, total: s.receiptCount, rate }].slice(-30);
    });
  }, [s?.capturedAt]);
  useEffect(() => {
    if (!creating && !selected) return;
    const previousFocus = document.activeElement as HTMLElement | null;
    document.querySelector<HTMLElement>(creating ? '.drawer input' : '.drawer button')?.focus();
    const listener = (e: KeyboardEvent) => { if (e.key === 'Escape') { setCreating(false); setSelected(null); } };
    window.addEventListener('keydown', listener); return () => { window.removeEventListener('keydown', listener); previousFocus?.focus(); };
  }, [creating, selected]);
  const title = pages.find(p => p.id === page)?.title;
  const rates = samples.slice(1), maxRate = Math.max(1, ...rates.map(x => x.rate));
  return <div className="app"><aside className="sidebar"><a className="brand" href="#overview"><span className="brand-mark"><Layers3 size={23} /></span><span>balance<span className="brand-light">lab</span><small>CONCURRENT BY DESIGN</small></span></a><span className="nav-caption">WORKSPACE</span><nav>{pages.map(p => <a key={p.id} href={'#' + p.id} className={page === p.id ? 'active' : ''} aria-current={page === p.id ? 'page' : undefined}><p.icon size={18} />{p.title}{p.id === 'lab' && <span className="new-tag">LAB</span>}</a>)}</nav><div className="sidebar-bottom"><div className="stack-badge"><Database size={18} /><div>Built for consistency<small>PostgreSQL · Redis · Kafka</small></div></div><a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">API documentation <ArrowUpRight size={14} /></a><small>Concurrent Balance Service / v1.0</small></div></aside>
    <div className="workspace"><header className="topbar"><div className="breadcrumbs">Workspace <span>/</span> <strong>{title}</strong></div><span className={'connection ' + (snapshot.isError ? 'offline' : s ? '' : 'waiting')}><i />{snapshot.isError ? 'Connection lost' : s ? 'Service connected' : 'Connecting…'}</span></header>
    <main><div className="page-heading"><div><span className="eyebrow">BALANCE LAB / {page === 'overview' ? 'LIVE WORKSPACE' : title?.toUpperCase()}</span><h1>{page === 'overview' ? 'Every unit, accounted for.' : title}</h1><p>{page === 'overview' ? 'Move balances. Follow the data. See concurrency in action.' : page === 'accounts' ? 'Explore authoritative balances and create accounts.' : page === 'transfers' ? 'Submit operations and trace their committed effects.' : page === 'lab' ? 'Explore the guarantees behind every balance update.' : 'Inspect requests made from this browser session.'}</p></div><div className="heading-actions"><button className="secondary" onClick={() => { refresh(); }} aria-label="Refresh data"><RefreshCw size={16} className={snapshot.isFetching ? 'spin' : ''} /></button><button className="primary" onClick={() => page === 'accounts' ? setCreating(true) : go('transfers')}><Plus size={16} />{page === 'accounts' ? 'New account' : 'New transaction'}</button></div></div>
    {snapshot.isError && <p className="notice error" role="alert">{(snapshot.error as Error).message} {s && 'Showing the last successful snapshot.'}<button className="text-button" onClick={refresh}>Retry</button></p>}
    {!s && snapshot.isPending && <div className="loading"><RefreshCw className="spin" />Loading committed data…</div>}
    {(page === 'overview' || page === 'accounts') && <div className="metrics">{[
      { label: 'Total balance', value: s ? units(s.totalBalance) : '—', suffix: 'integer units', icon: Wallet },
      { label: 'Accounts', value: s?.accountCount.toLocaleString() ?? '—', suffix: 'persistent accounts', icon: Database },
      { label: 'Committed operations', value: s?.receiptCount.toLocaleString() ?? '—', suffix: 'unique transaction IDs', icon: CheckCircle2 },
      { label: 'Outbox backlog', value: s?.pendingEvents.toLocaleString() ?? '—', suffix: s?.kafkaEnabled ? 'Kafka publishing enabled' : 'Kafka publishing disabled', icon: Layers3 },
    ].map(m => <div className="metric" key={m.label}><div><span>{m.label}</span><m.icon size={17} /></div><strong>{m.value}</strong><small>{m.suffix}</small></div>)}</div>}
    {page === 'overview' && <><div className="overview-grid"><Panel title="Committed activity" subtitle="New unique operations per second · observed in this browser" action={<span className="live-tag"><i />{auto && !snapshot.isError ? "LIVE" : "PAUSED"}</span>}><div className="activity-chart"><div className="chart-axis"><span>{maxRate.toFixed(1)}</span><span>0</span></div><div className="bars">{rates.length ? rates.map(x => <div key={x.time} className="bar-slot" title={new Date(x.time).toLocaleTimeString() + ': ' + x.rate.toFixed(2) + ' commits/s'}><div style={{ height: (x.rate ? Math.max(4, x.rate / maxRate * 100) : 0) + '%' }} /></div>) : <p className="chart-empty">Collecting live observations…</p>}</div></div><div className="chart-footer"><span>Last {rates.length > 1 ? Math.round((rates.at(-1)!.time - rates[0].time) / 1000) : 0} seconds</span><span><i />Committed receipts</span></div></Panel>
      <section className="lab-promo"><div className="promo-icon"><FlaskConical size={27} /></div><span className="eyebrow">BREAK THE RACE, NOT THE BALANCE</span><h2>What happens when<br />requests collide?</h2><p>Try competing transfers and duplicate requests. Watch the database prove its guarantees.</p><button onClick={() => go('lab')}>Open concurrency lab <ArrowUpRight size={17} /></button><div className="promo-decoration" aria-hidden="true">A ⇄ B</div></section></div>
      <Panel title="Recent transactions" subtitle="Successful operations from durable database receipts" action={<button className="text-button" onClick={() => go('transfers')}>View all <ArrowRight size={14} /></button>}><Receipts receipts={s?.receipts.slice(0, 6) || []} select={setSelected} /></Panel><div className="architecture"><span><Database size={16} />PostgreSQL is the source of truth</span><ArrowRight size={14} /><span>Redis accelerates idempotency reads</span><ArrowRight size={14} /><span>Kafka publishes committed changes</span></div></>}
    {page === 'accounts' && <Panel title="Account directory" subtitle="20 accounts per page · select history to inspect related transactions" action={<button className="text-button" onClick={() => setCreating(true)}><Plus size={15} />Create account</button>}>{s?.accounts.length ? <div className="table-scroll"><table><thead><tr><th>Account ID</th><th>Available balance</th><th>History</th></tr></thead><tbody>{s.accounts.map(a => <tr key={a.id}><td><span className="account-avatar"><Wallet size={15} /></span><span className="mono">{a.id}</span></td><td className="numeric">{units(a.balance)} <small className="inline">units</small></td><td><button className="text-button" onClick={() => { setFilter(a.id); setFilterDraft(a.id); setReceiptOffset(0); go('transfers'); }}>View history <ArrowRight size={14} /></button></td></tr>)}</tbody></table></div> : <div className="empty"><Wallet size={28} /><h3>Your first account starts here</h3><p>Create two accounts to explore transfers.</p><button className="primary" onClick={() => setCreating(true)}><Plus size={16} />Create account</button></div>}<Pager offset={accountOffset} total={s?.accountCount || 0} set={setAccountOffset} /></Panel>}
    {page === 'transfers' && <><Panel title="Move a balance" subtitle="All operations go through the existing balance API"><OperationForm refresh={refresh} accounts={s?.accounts || []} /></Panel><Panel title="Committed history" subtitle="Only successful operations are persisted. Rejected attempts appear in the API inspector." action={<form className="filter" onSubmit={e => { e.preventDefault(); setFilter(filterDraft); setReceiptOffset(0); }}><input aria-label="Filter history by exact account ID" placeholder="Exact account ID" maxLength={255} value={filterDraft} onChange={e => setFilterDraft(e.target.value)} /><button className="secondary">Filter</button>{filter && <button type="button" className="text-button" onClick={() => { setFilter(''); setFilterDraft(''); setReceiptOffset(0); }}>Clear</button>}</form>}><Receipts receipts={s?.receipts || []} select={setSelected} /><Pager offset={receiptOffset} total={s?.filteredReceiptCount || 0} set={setReceiptOffset} /></Panel></>}
    {page === 'lab' && <Lab enabled={s?.labEnabled || false} refresh={refresh} />}
    {page === 'api' && <Panel title="Request inspector" subtitle="Last 100 explicit actions in this tab · polling is omitted · durations are browser round trips" action={<button className="text-button" onClick={() => setLogs([])}>Clear log</button>}>{logs.length ? <div className="table-scroll"><table><thead><tr><th>Method / time</th><th>Endpoint</th><th>HTTP status</th><th>Duration</th><th>Details</th></tr></thead><tbody>{logs.map(l => <tr key={l.id}><td><span className="tag">{l.method}</span><small>{new Date(l.time).toLocaleTimeString()}</small></td><td className="mono">{l.path}</td><td><span className={l.status >= 200 && l.status < 300 ? 'status' : 'error-text'}>{l.status || 'Network error'}</span></td><td>{l.duration.toFixed(0)} ms</td><td><button className="text-button" onClick={() => setSelected(l)}>Inspect <Code2 size={14} /></button></td></tr>)}</tbody></table></div> : <div className="empty"><Code2 size={30} /><h3>The API, with the lights on</h3><p>Create an account, submit a transfer, or run an experiment to inspect the request and response here.</p></div>}</Panel>}
    <footer><span><span className="footer-dot" />Authoritative data · {s ? 'Captured ' + new Date(s.capturedAt).toLocaleTimeString() : 'Awaiting snapshot'}</span><label className="toggle"><input type="checkbox" checked={auto} onChange={e => setAuto(e.target.checked)} />Auto-refresh every 2s</label></footer></main></div>
    {(creating || selected) && <div className="overlay" onClick={() => { setCreating(false); setSelected(null); }}><section className="drawer" role="dialog" aria-modal="true" aria-label={creating ? 'Create account' : 'Inspect details'} onClick={e => e.stopPropagation()} onKeyDown={e => { if (e.key === 'Tab') { const elements = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('button, input, a[href], summary')).filter(x => !(x as HTMLButtonElement).disabled); const first = elements[0], last = elements.at(-1); if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); } else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); } } }}><div className="drawer-heading"><div><span className="eyebrow">BALANCE LAB</span><h2>{creating ? 'Create account' : selected && 'method' in selected ? 'API request details' : 'Committed transaction'}</h2></div><button className="icon-button" aria-label="Close details" onClick={() => { setCreating(false); setSelected(null); }}><X size={20} /></button></div>{creating ? <CreateAccount done={() => { setCreating(false); refresh(); }} /> : selected && 'method' in selected ? <div className="inspection"><span className="tag">{selected.method} {selected.status || 'UNKNOWN'}</span><h3 className="mono">{selected.path}</h3><p>{selected.duration.toFixed(2)} ms · browser round trip</p><h3>Request body</h3><pre>{selected.request || '(no body)'}</pre><h3>Response body</h3><pre>{selected.response || '(empty response)'}</pre></div> : <div className="inspection"><span className="status"><CheckCircle2 size={14} />Committed</span><pre>{JSON.stringify(selected, null, 2)}</pre><p className="hint">This receipt proves a committed effect. A later account balance can include subsequent operations.</p></div>}</section></div>}
  </div>;
}
