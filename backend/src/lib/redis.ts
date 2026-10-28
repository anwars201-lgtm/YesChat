import Redis from 'ioredis';

let redis: Redis | null = null;
let subscriberClient: Redis | null = null;

/**
 * Build the ioredis options object with `keyPrefix` derived from
 * `REDIS_PREFIX`. The platform sets `REDIS_PREFIX=proj_<projectId>` on
 * every customer container so that ALL key reads/writes/pub-sub channels
 * are auto-namespaced to that prefix — even when callers forget to add
 * the prefix manually (e.g. `cached('users:list')` becomes
 * `proj_<id>:users:list` at the wire level).
 *
 * ioredis applies `keyPrefix` to standard key commands (get/set/del/
 * scan/incr/multi/pipeline), but NOT to pub/sub channel arguments or raw
 * `client.call(...)` invocations. `realtime.ts` therefore namespaces channels
 * explicitly, while `rate-limit-redis` keeps its own manual key prefix.
 *
 * Defence-in-depth: even if the container is mis-provisioned and ends
 * up with full-Redis credentials (admin URL fallback), the keyPrefix
 * means every WRITE this app does still lands inside its own namespace.
 * It cannot accidentally clobber `bull:apk-builds:*` or another
 * tenant's `proj_*:*` keys.
 *
 * q55vzy6sgk8x post-mortem (Fix G). The build that triggered this fix
 * spammed `[ioredis] Unhandled error event: NOAUTH Authentication
 * required.` ~120 times in a 13-minute window because a transient
 * customer-Redis credential rotation took effect mid-build. ioredis's
 * default behaviour is to retry forever on connection error, and
 * Authentication failures (WRONGPASS / NOAUTH) are logically permanent
 * — every retry will see the same failure. We now (1) stop retrying
 * on auth-class errors via `reconnectOnError`, (2) cap the exponential
 * retry backoff at 30s with a 5-attempt ceiling, and (3) dedupe
 * console.warn for repeat error messages so a single broken connection
 * can't fill the build log with thousands of identical lines.
 */
function buildOptions(extra: Record<string, unknown> = {}): Record<string, unknown> {
  const prefix = process.env.REDIS_PREFIX;
  return {
    maxRetriesPerRequest: 3,
    // Tenant ACLs deliberately cannot run INFO. ioredis' default ready-check
    // sends INFO and emits a misleading NOPERM warning even though normal
    // namespaced commands are healthy.
    enableReadyCheck: false,
    // Cap exponential backoff at 30s, give up after 5 attempts.
    retryStrategy(times: number): number | null {
      if (times >= 5) return null;
      return Math.min(50 * Math.pow(2, times), 30_000);
    },
    // Auth errors are permanent — refuse to reconnect and drop the
    // client entirely. Anything else (ECONNREFUSED, READONLY when a
    // primary fails over, etc.) gets a normal reconnect.
    reconnectOnError(err: Error): boolean {
      const msg = err.message || '';
      if (/^(NOAUTH|WRONGPASS|NOPERM)/.test(msg)) return false;
      return true;
    },
    ...(prefix ? { keyPrefix: `${prefix}:` } : {}),
    ...extra,
  };
}

/**
 * Dedupe identical error messages so a single misbehaving connection
 * can't print the same warning 120× per minute. We keep a small LRU
 * of recent message strings; the FIRST occurrence prints normally, the
 * 2nd through Nth print a "(suppressed N×)" line at the next 30s
 * window boundary. The set is per-process; container restart resets it.
 */
const errorDedupeWindow = new Map<string, { count: number; firstAt: number; lastFlushAt: number }>();
function logRedisErrorDeduped(label: string, err: Error): void {
  const key = `${label}:${err.message}`;
  const now = Date.now();
  const entry = errorDedupeWindow.get(key);
  if (!entry) {
    errorDedupeWindow.set(key, { count: 1, firstAt: now, lastFlushAt: now });
    // eslint-disable-next-line no-console
    console.warn(`[${label}] ${err.message}`);
    return;
  }
  entry.count += 1;
  // Flush a "(suppressed Nx)" line once every 30s when we've actually
  // seen multiple repeats — otherwise a single benign reconnect prints
  // a noisy "(suppressed 1x)" line.
  if (now - entry.lastFlushAt > 30_000 && entry.count > 1) {
    const since = Math.round((now - entry.lastFlushAt) / 1000);
    // eslint-disable-next-line no-console
    console.warn(`[${label}] ${err.message} (suppressed ${entry.count - 1}× in ${since}s)`);
    entry.lastFlushAt = now;
    entry.count = 0;
  }
}

export function getRedis(): Redis | null {
  if (redis) return redis;
  const url = process.env.REDIS_URL;
  if (!url) return null;

  try {
    redis = new Redis(url, buildOptions({ lazyConnect: true }));
    redis.on('error', (err: Error) => {
      logRedisErrorDeduped('redis', err);
      if (/^(NOAUTH|WRONGPASS|NOPERM)/.test(err.message || '')) {
        // Auth-permanent errors — drop the client so subsequent
        // getRedis() calls return null instead of dragging along a
        // dead connection. Caller code already treats `getRedis()
        // === null` as "cache disabled".
        try {
          redis?.disconnect();
        } catch {
          /* noop */
        }
        redis = null;
      }
    });
    redis.connect().catch((err: Error) => {
      logRedisErrorDeduped('redis', err);
      redis = null;
    });
    return redis;
  } catch {
    return null;
  }
}

/**
 * Dedicated Redis client in subscriber mode. ioredis locks a connection
 * that has called `subscribe()` into subscriber-only mode — it can no
 * longer send regular commands like `publish`, `get`, or `set`. Using
 * the shared `getRedis()` connection for both sub + pub would deadlock
 * every other Redis-dependent feature (cache, rate-limit) the moment a
 * client connects to the SSE channel.
 *
 * This factory returns a SEPARATE connection that is reused across all
 * SSE subscriptions. Clean up on process exit happens via ioredis's
 * default disconnect behavior.
 */
export function getRedisSubscriber(): Redis | null {
  if (subscriberClient) return subscriberClient;
  const url = process.env.REDIS_URL;
  if (!url) return null;

  try {
    subscriberClient = new Redis(url, buildOptions());
    subscriberClient.on('error', (err: Error) => {
      logRedisErrorDeduped('redis-sub', err);
      if (/^(NOAUTH|WRONGPASS|NOPERM)/.test(err.message || '')) {
        try {
          subscriberClient?.disconnect();
        } catch {
          /* noop */
        }
        subscriberClient = null;
      }
    });
    return subscriberClient;
  } catch {
    return null;
  }
}

/** Simple cache helper — returns cached value or calls fn and caches result */
export async function cached<T>(key: string, ttlSeconds: number, fn: () => Promise<T>): Promise<T> {
  const r = getRedis();
  if (r) {
    try {
      const hit = await r.get(key);
      if (hit) return JSON.parse(hit) as T;
    } catch {
      /* cache miss */
    }
  }

  const result = await fn();

  if (r) {
    r.set(key, JSON.stringify(result), 'EX', ttlSeconds).catch(() => {});
  }

  return result;
}
