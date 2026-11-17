import { describe, expect, it, afterEach, vi } from 'vitest';
import { api, ApiError, decimal, encode, observeApi, parseResponse, units } from './api';
afterEach(() => { vi.unstubAllGlobals(); });
describe('exact decimal amounts', () => {
  it('encodes large and fractional money without passing through a JS number', () => {
    expect(encode({ amount: '9223372036854775807', transactionId: 'tx-1' }))
      .toBe('{"amount":9223372036854775807,"transactionId":"tx-1"}');
    expect(units('9007199254740993')).toBe('9,007,199,254,740,993');
    expect(decimal('00010.1250')).toBe('10.1250');
    expect(encode({ amount: '9223372036854775808.1234567890123456789' })).toBe('{"amount":9223372036854775808.1234567890123456789}');
    expect(units('9007199254740993.000000000000001')).toBe('9,007,199,254,740,993.000000000000001');
    expect(JSON.parse(encode({ initialBalance: '0', accountId: 'a\"b' }))).toEqual({ initialBalance: 0, accountId: 'a\"b' });
  });
  it.each(['-1', '0', '0.000', '1e3', ' 2', '1.', '.2', 'x'])('rejects invalid amount %s', value => {
    expect(() => decimal(value)).toThrow();
  });
  it('preserves numeric money and large IDs from business responses before JSON parsing', () => {
    expect(parseResponse('{"balance":9007199254740993.123456789,"amount":1.25e-10,"offset":9007199254740993,"count":2,"label":"amount: 123"}'))
      .toEqual({ balance: '9007199254740993.123456789', amount: '1.25e-10', offset: '9007199254740993', count: 2, label: 'amount: 123' });
    expect(parseResponse('{"balance":"0.0001","nested":{"amount":20.00},"text":"escaped \\\" balance"}'))
      .toEqual({ balance: '0.0001', nested: { amount: '20.00' }, text: 'escaped " balance' });
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
    expect(await api('/accounts/A/balance')).toEqual({ balance: '9223372036854775807' });
    expect(listener.mock.calls[0][0].response).toContain('9223372036854775807');
    stop();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"detail":"Insufficient funds"}', { status: 409 })));
    await expect(api('/accounts/A/debit', { amount: '10', transactionId: 'tx' }))
      .rejects.toBeInstanceOf(ApiError);
  });
  it('exposes field errors and treats a server failure during mutation as uncertain', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"fieldErrors":{"amount":["must be positive"]}}', { status: 400 })));
    await expect(api('/accounts/A/debit', { amount: '1', transactionId: 'tx' })).rejects.toMatchObject({ uncertain: false, fieldErrors: { amount: ['must be positive'] } });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"detail":"Internal error"}', { status: 500 })));
    await expect(api('/accounts/A/debit', { amount: '1', transactionId: 'tx' })).rejects.toMatchObject({ uncertain: true });
  });
});
