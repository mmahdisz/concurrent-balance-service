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
  constructor(message: string, public status: number, public uncertain = false) { super(message); }
}
// The service stores whole integer units in BIGINT. Preserve every digit over JSON.
export function integer(value: string, allowZero = false): string {
  if (!/^\d+$/.test(value)) throw new Error('Enter an amount in whole integer units.');
  const n = BigInt(value);
  if (n < (allowZero ? 0n : 1n) || n > 9223372036854775807n) throw new Error('Amount is outside the supported range.');
  return n.toString();
}
export function encode(body: Record<string, unknown>): string {
  return '{' + Object.entries(body).map(([key, value]) =>
    JSON.stringify(key) + ':' + (key === 'amount' || key === 'initialBalance'
      ? integer(String(value), key === 'initialBalance') : JSON.stringify(value))).join(',') + '}';
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
    try { data = response ? JSON.parse(response) : null; }
    catch { throw new ApiError('The server returned an unexpected response.', status, !!body); }
    if (!result.ok) {
      const fields = data?.fieldErrors ? Object.entries(data.fieldErrors).map(([k,v]) => k + ': ' + String(v)).join(' · ') : '';
      throw new ApiError(fields || data?.detail || data?.message || 'Request failed (' + status + ')', status);
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
export const units = (value: string) => BigInt(value).toLocaleString('en-US');
