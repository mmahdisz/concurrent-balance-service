import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError, encode, type Account } from './api';
import { useText } from './i18n';

const accountPattern = '(?!\\.{1,2}$)[A-Za-z0-9._~\\-]+';
type Draft = { operation: string; source: string; destination: string; amount: string; transactionId: string };
export function OperationForm({ accounts, refresh, initialAccount = '' }: { accounts: Account[]; refresh: () => void; initialAccount?: string }) {
  const t = useText();
  const [draft, setDraft] = useState<Draft>({ operation: 'transfer', source: initialAccount, destination: '', amount: '20.125', transactionId: crypto.randomUUID() });
  const [last, setLast] = useState<Draft | null>(null), [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(''), [error, setError] = useState<ApiError | null>(null);
  const [conflict, setConflict] = useState(false);
  useEffect(() => { if (initialAccount) setDraft(previous => ({ ...previous, source: initialAccount, transactionId: crypto.randomUUID() })); }, [initialAccount]);
  function edit(field: keyof Draft, value: string) {
    setDraft(previous => ({ ...previous, [field]: value,
      transactionId: field !== 'transactionId' && last && !conflict ? crypto.randomUUID() : field === 'transactionId' ? value : previous.transactionId }));
    setMessage(''); setError(null);
  }
  const body = (value: Draft) => ({ amount: value.amount, transactionId: value.transactionId,
    ...(value.operation === 'transfer' ? { destinationAccountId: value.destination } : {}) });
  async function send(value: Draft) {
    setBusy(true); setMessage(''); setError(null);
    try {
      // Save exactly what was submitted, including when the result becomes unknown.
      encode(body(value)); setLast({ ...value });
      await api('/accounts/' + encodeURIComponent(value.source) + '/' + value.operation, body(value));
      setMessage(t('Acknowledged. A retry can return a newer balance; it does not replay the original response.', 'درخواست تأیید شد. تلاش مجدد ممکن است موجودی جدیدتری برگرداند؛ پاسخ قبلی بازپخش نمی‌شود.')); refresh();
    } catch (e) { setError(e instanceof ApiError ? e : new ApiError((e as Error).message, 0)); }
    finally { setBusy(false); }
  }
  function submit(e: FormEvent) { e.preventDefault(); void send(draft); }
  const fieldError = (field: string) => error?.fieldErrors[field]?.length
    ? <span id={'error-' + field} className="field-error">{error.fieldErrors[field].join(' · ')}</span> : null;
  const invalid = (field: string) => ({ 'aria-invalid': !!error?.fieldErrors[field]?.length,
    'aria-describedby': error?.fieldErrors[field]?.length ? 'error-' + field : undefined });
  let preview = '';
  try { preview = encode(body(draft)); } catch { preview = t('Enter a valid decimal amount for a preview.', 'برای پیش‌نمایش، مبلغ اعشاری معتبر وارد کنید.'); }
  return <form onSubmit={submit} className="operation-form">
    <fieldset disabled={busy}><legend>{t('Operation', 'نوع عملیات')}</legend><div className="segmented">{['transfer', 'credit', 'debit'].map((operation, i) => <button type="button" key={operation} aria-pressed={draft.operation === operation} className={draft.operation === operation ? 'selected' : ''} onClick={() => edit('operation', operation)}>{[t('transfer', 'انتقال'), t('credit', 'واریز'), t('debit', 'برداشت')][i]}</button>)}</div>
    <datalist id="account-options">{accounts.map(a => <option value={a.id} key={a.id} />)}</datalist>
    <div className="form-grid">
      <label>{draft.operation === 'transfer' ? t('From account', 'حساب مبدأ') : t('Account ID', 'شناسه حساب')}<input dir="ltr" required pattern={accountPattern} maxLength={255} list="account-options" value={draft.source} onChange={e => edit('source', e.target.value)} /></label>
      {draft.operation === 'transfer' && <label>{t('To account', 'حساب مقصد')}<input dir="ltr" required pattern={accountPattern} maxLength={255} list="account-options" value={draft.destination} onChange={e => edit('destination', e.target.value)} {...invalid('destinationAccountId')} />{fieldError('destinationAccountId')}</label>}
      <label>{t('Amount · exact units', 'مبلغ · واحد دقیق')}<input dir="ltr" required inputMode="decimal" pattern="[0-9]+(\.[0-9]+)?" value={draft.amount} onChange={e => edit('amount', e.target.value)} {...invalid('amount')} />{fieldError('amount')}</label>
      <label className="wide">{t('Transaction ID', 'شناسه تراکنش')}<div className="input-action"><input aria-label={t('Transaction ID', 'شناسه تراکنش')} dir="ltr" required maxLength={255} value={draft.transactionId} onChange={e => edit('transactionId', e.target.value)} {...invalid('transactionId')} /><button type="button" onClick={() => edit('transactionId', crypto.randomUUID())}>{t('New ID', 'شناسه جدید')}</button></div>{fieldError('transactionId')}</label>
    </div>
    <label className="toggle"><input type="checkbox" checked={conflict} onChange={e => { setConflict(e.target.checked); if (!e.target.checked) edit('transactionId', crypto.randomUUID()); }} />{t('Deliberate conflict: keep ID when editing payload', 'آزمایش تعارض: حفظ شناسه هنگام تغییر اطلاعات')}</label>
    <p className="hint">{t('Editing a submitted payload generates a new ID by default. Retry below resends the original payload unchanged.', 'تغییر اطلاعات ارسال‌شده به‌طور پیش‌فرض شناسه جدید می‌سازد. تلاش مجدد، اطلاعات اصلی را بدون تغییر می‌فرستد.')}</p>
    <details><summary>{t('Inspect draft request', 'مشاهده پیش‌نویس درخواست')}</summary><pre dir="ltr">POST /accounts/{encodeURIComponent(draft.source)}/{draft.operation}{'\n'}{preview}</pre></details>
    <div className="heading-actions"><button className="primary" disabled={draft.operation === 'transfer' && !!draft.source && draft.source === draft.destination}>{busy ? t('Submitting…', 'در حال ارسال…') : t('Submit ' + draft.operation, 'ارسال درخواست')}</button>
    {last && <button type="button" className="secondary" onClick={() => void send(last)}>{t('Retry original payload', 'تلاش مجدد با اطلاعات اصلی')}</button>}</div>
    </fieldset>
    {message && <p role="status" className="notice success">{message}</p>}
    {error && <p role="alert" className="notice error">{error.uncertain && <strong>{t('Outcome unknown. Check receipts before retrying. ', 'نتیجه نامشخص است. پیش از تلاش مجدد رسیدها را بررسی کنید. ')}</strong>}{error.message}</p>}
  </form>;
}

export function CreateAccount({ done }: { done: () => void }) {
  const t = useText();
  const [id, setId] = useState(''), [balance, setBalance] = useState('1000'), [busy, setBusy] = useState(false), [error, setError] = useState<ApiError | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError(null);
    try { await api('/accounts', { accountId: id, initialBalance: balance }); done(); }
    catch (e) { setError(e instanceof ApiError ? e : new ApiError((e as Error).message, 0)); } finally { setBusy(false); }
  }
  return <form onSubmit={submit} className="operation-form"><fieldset disabled={busy}>
    <label>{t('Account ID', 'شناسه حساب')}<input dir="ltr" required pattern={accountPattern} maxLength={255} value={id} onChange={e => setId(e.target.value)} aria-invalid={!!error?.fieldErrors.accountId} aria-describedby={error?.fieldErrors.accountId ? 'create-id-error' : undefined} /></label>
    {error?.fieldErrors.accountId && <p className="field-error" id="create-id-error">{error.fieldErrors.accountId.join(' · ')}</p>}
    <label>{t('Initial balance · exact units', 'موجودی اولیه · واحد دقیق')}<input dir="ltr" required pattern="[0-9]+(\.[0-9]+)?" inputMode="decimal" value={balance} onChange={e => setBalance(e.target.value)} aria-invalid={!!error?.fieldErrors.initialBalance} aria-describedby={error?.fieldErrors.initialBalance ? 'create-balance-error' : undefined} /></label>
    {error?.fieldErrors.initialBalance && <p className="field-error" id="create-balance-error">{error.fieldErrors.initialBalance.join(' · ')}</p>}
    <p className="hint">{t('Exact decimal units. No currency, fixed scale, or rounding is assumed. IDs accept ASCII letters, digits, dot, underscore, tilde and hyphen; dot-only IDs are excluded.', 'واحد اعشاری دقیق؛ بدون فرض ارز، تعداد رقم اعشار یا گرد کردن. شناسه شامل حروف لاتین، عدد، نقطه، زیرخط، مد و خط تیره است؛ یک یا دو نقطه به‌تنهایی مجاز نیست.')}</p>
    <button className="primary">{busy ? t('Creating…', 'در حال ایجاد…') : t('Create account', 'ایجاد حساب')}</button></fieldset>{error && <p className="notice error" role="alert">{error.uncertain && t('Outcome unknown. Look up the account before retrying. ', 'نتیجه نامشخص است. پیش از تلاش مجدد حساب را بررسی کنید. ')}{error.message}</p>}</form>;
}
