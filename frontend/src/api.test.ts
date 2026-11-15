import { describe, expect, it, afterEach, vi } from 'vitest';
import { api, ApiError, encode, integer, observeApi, units } from './api';
afterEach(() => { vi.unstubAllGlobals(); });
describe('exact integer amounts', () => {
  it('encodes the largest BIGINT without passing through a JS number', () => {
    expect(encode({ amount: '9223372036854775807', transactionId: 'tx-1' }))
      .toBe('{"amount":9223372036854775807,"transactionId":"tx-1"}');
    expect(units('9007199254740993')).toBe('9,007,199,254,740,993');
    expect(integer('00010')).toBe('10');
    expect(JSON.parse(encode({ initialBalance: '0', accountId: 'a\"b' }))).toEqual({ initialBalance: 0, accountId: 'a\"b' });
  });
  it.each(['-1', '0', '1.2', '1e3', ' 2', '9223372036854775808', 'x'])('rejects invalid amount %s', value => {
    expect(() => integer(value)).toThrow();
  });
});
describe('API failure semantics', () => {
  it('does not retry mutations and reports network loss as uncertain', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('offline'));
    vi.stubGlobal('fetch', fetch);
    await expect(api('/accounts/A/transfer', { amount: '1', transactionId: 'stable-id', destinationAccountId: 'B' }))
      .rejects.toMatchObject({ uncertain: true, status: 0 });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('retains raw response digits in the inspector and preserves a conflict code', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"balance":9223372036854775807}', { status: 200 })));
    const listener = vi.fn(), stop = observeApi(listener);
    await api('/accounts/A/balance');
    expect(listener.mock.calls[0][0].response).toContain('9223372036854775807');
    stop();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"detail":"Insufficient funds"}', { status: 409 })));
    await expect(api('/accounts/A/debit', { amount: '10', transactionId: 'tx' }))
      .rejects.toBeInstanceOf(ApiError);
  });
});
