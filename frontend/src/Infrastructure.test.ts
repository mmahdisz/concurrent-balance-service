import { describe, expect, it } from 'vitest';
import { formatMetric, sampleStale } from './Infrastructure';

describe('monitoring evidence', () => {
  it('distinguishes missing and non-finite samples from measured zero', () => {
    expect(formatMetric(null, 'bytes')).toBe('—');
    expect(formatMetric(NaN, 'seconds')).toBe('—');
    expect(formatMetric(0, 'cores')).toBe('0');
    expect(formatMetric(1048576, 'bytes')).toBe('1.0 MiB');
    expect(formatMetric(0.125, 'seconds')).toBe('125.0 ms');
  });
  it('rejects stale, invalid, and far-future observation timestamps', () => {
    const now = Date.now();
    const sample = (time: number) => ({ labels: {}, value: 1, observedAt: new Date(time).toISOString() });
    expect(sampleStale(sample(now), now)).toBe(false);
    expect(sampleStale(sample(now - 46000), now)).toBe(true);
    expect(sampleStale(sample(now + 46000), now)).toBe(true);
    expect(sampleStale({ labels: {}, value: 1, observedAt: 'invalid' }, now)).toBe(true);
  });
});
