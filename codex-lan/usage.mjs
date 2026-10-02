const number = value => typeof value === 'number' && Number.isFinite(value) ? value : null;
export function normalizeUsage(data, now = Date.now()) {
  const mapped = data?.rateLimitsByLimitId;
  const buckets = mapped && typeof mapped === 'object' && Object.keys(mapped).length
    ? Object.entries(mapped) : data?.rateLimits ? [[data.rateLimits.limitId ?? 'codex', data.rateLimits]] : [];
  return { fetchedAt: now, limits: buckets.filter(([, b]) => b && typeof b === 'object').map(([id, b]) => ({
    id, name: b.limitName || b.normalModelSlug || id,
    windows: ['primary', 'secondary'].filter(key => b[key]).map(key => {
      const w = b[key], used = number(w.usedPercent);
      return { kind: key, remainingPercent: used === null ? null : Math.max(0, Math.min(100, 100 - used)),
        windowDurationMins: number(w.windowDurationMins), resetsAt: number(w.resetsAt) };
    }),
    credits: b.credits ? { unlimited: b.credits.unlimited === true,
      balance: typeof b.credits.balance === 'string' && /^\d+(\.\d+)?$/.test(b.credits.balance) ? b.credits.balance : null } : null,
  })) };
}
