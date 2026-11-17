export type Account = { id: string; balance: string };
export type Receipt = { transactionId: string; type: string; source: string; destination: string | null; amount: string; processedAt: string };
export type Snapshot = {
  capturedAt: string; accountCount: number; totalBalance: string; receiptCount: number;
  pendingEvents: number; kafkaEnabled: boolean; labEnabled: boolean;
  accounts: Account[]; receipts: Receipt[]; filteredReceiptCount: number;
};
export type Scenario = 'COMPETING' | 'DUPLICATE' | 'OPPOSING' | 'BURST';
export type Attempt = { index: number; transactionId: string; source: string; destination: string; amount: number; outcome: string; durationMs: number };
export type Run = {
  id: string; state: 'RUNNING' | 'COMPLETED' | 'FAILED'; scenario: Scenario; startedAt: string;
  requested: number; acknowledged: number; rejected: number; errors: number; committedReceipts: number;
  balances: Record<string, string>; conserved: boolean; nonnegative: boolean; expectedEffect: boolean;
  durationMs: number; p95Ms: number; attempts: Attempt[]; message: string;
};
export type ApiEntry = { id: string; time: string; method: string; path: string; request: string; response: string; status: number; duration: number };
export class ApiError extends Error {
  constructor(message: string, public status: number, public uncertain = false,
    public fieldErrors: Record<string, string[]> = {}) { super(message); }
}
// BigDecimal / PostgreSQL NUMERIC: no currency, scale, or BIGINT limit is assumed.
export function decimal(value: string, allowZero = false): string {
  if (!/^\d+(?:\.\d+)?$/.test(value)) throw new Error('Enter an exact decimal amount, for example 20.125.');
  const [whole, fraction] = value.split('.');
  const normalized = whole.replace(/^0+(?=\d)/, '') + (fraction ? '.' + fraction : '');
  if (!allowZero && !/[1-9]/.test(normalized)) throw new Error('Amount must be greater than zero.');
  return normalized;
}
export function encode(body: Record<string, unknown>): string {
  return '{' + Object.entries(body).map(([key, value]) =>
    JSON.stringify(key) + ':' + (key === 'amount' || key === 'initialBalance'
      ? decimal(String(value), key === 'initialBalance') : JSON.stringify(value))).join(',') + '}';
}
// Tokenize JSON before parsing: numeric money never passes through Number.
// Quote unsafe integers too, so sequence IDs remain exact. Strings are untouched.
export function parseResponse(raw: string): unknown {
  const tokens = raw.match(/"(?:[^"\\]|\\.)*"|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?|\s+|./gs) || [];
  let key = '';
  const transformed = tokens.map((token, index) => {
    if (token.startsWith('"')) {
      let next = index + 1;
      while (/^\s+$/.test(tokens[next] || '')) next++;
      if (tokens[next] === ':') key = JSON.parse(token) as string;
      return token;
    }
    if (/^-?\d/.test(token) && (['amount', 'balance', 'initialBalance', 'totalBalance'].includes(key)
      || (!/[.eE]/.test(token) && !Number.isSafeInteger(Number(token))))) return JSON.stringify(token);
    return token;
  }).join('');
  return JSON.parse(transformed);
}
let observer: ((entry: ApiEntry) => void) | undefined;
export function observeApi(listener: (entry: ApiEntry) => void) { observer = listener; return () => { observer = undefined; }; }
export async function api<T>(path: string, body?: Record<string, unknown>, inspect = true): Promise<T> {
  const method = body ? 'POST' : 'GET';
  const request = body ? encode(body) : '';
  const begin = performance.now();
  let response = '', status = 0;
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 45000);
  try {
    const result = await fetch(path, { method, body: request || undefined,
      headers: body ? { 'Content-Type': 'application/json' } : {}, signal: abort.signal });
    status = result.status;
    response = await result.text();
    let data;
    try { data = response ? parseResponse(response) : null; }
    catch { throw new ApiError('The server returned an unexpected response.', status, !!body); }
    if (!result.ok) {
      const problem = data as { fieldErrors?: Record<string, string[]>; detail?: string; message?: string } | null;
      const fields = problem?.fieldErrors ? Object.entries(problem.fieldErrors).map(([k,v]) => k + ': ' + String(v)).join(' · ') : '';
      throw new ApiError(fields || problem?.detail || problem?.message || 'Request failed (' + status + ')', status,
        !!body && status >= 500, problem?.fieldErrors || {});
    }
    return data as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    response = String(error);
    throw new ApiError(body ? 'Connection interrupted. Outcome unknown. Check history and reuse the same transaction ID when retrying.'
      : 'Cannot reach the service. Check the backend and retry.', status, !!body);
  } finally {
    clearTimeout(timer);
    if (inspect) observer?.({ id: crypto.randomUUID(), time: new Date().toISOString(), method, path, request, response, status, duration: performance.now() - begin });
  }
}
export const units = (value: string) => {
  const match = /^(-?)(\d+)(\.\d+)?$/.exec(value);
  return match ? match[1] + match[2].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (match[3] || '') : value;
};
export const timestamp = (value: string) => new Date(value).toLocaleString('en-GB', { timeZone: 'UTC', hour12: false }) + ' UTC';
