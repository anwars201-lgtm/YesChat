import rateLimit, { Store, ClientRateLimitInfo, IncrementResponse } from 'express-rate-limit';
import jwt from 'jsonwebtoken';
import type { Redis } from 'ioredis';
import { getRedis } from '../lib/redis';

// Initialize Redis client once
const redisClient = getRedis();

// Rate-limit store that uses INCR + EXPIRE NX (Redis 7.0+) instead of Lua
// scripting. The customer-Redis ACL blocks @scripting / EVAL / EVALSHA, so the
// previous `rate-limit-redis` package (which compiles a Lua script via
// SCRIPT|LOAD on first call) crashes the backend on the very first request.
// See ADR-0014 for the ACL design.
//
// Implementation contract — must match what `rate-limit-redis` produced on the
// wire so customers don't get sudden quota resets:
//
//   prefix = `${REDIS_PREFIX}:global:`
//   wireKey = `${prefix}user:<userId>`  (no double keyPrefix-application)
//
// We bypass ioredis's `keyPrefix` by using raw `.call('INCR', ...)` for every
// command in the MULTI block. If we mixed `.incr(key)` (auto-prefixed) with
// `.call('EXPIRE', key, ...)` (raw) we'd get TWO different wire keys: INCR
// would write to `proj_<id>:proj_<id>:global:user:1` while EXPIRE would set
// TTL on `proj_<id>:global:user:1` — TTL would never apply, the counter
// would grow forever, customers get rate-limited after 100 requests for life.
class IncrExpireStore implements Store {
  windowSec = 0;
  prefix: string;

  constructor(
    private client: Redis,
    prefix: string,
  ) {
    this.prefix = prefix;
  }

  init(opts: { windowMs: number }): void {
    this.windowSec = Math.max(1, Math.ceil(opts.windowMs / 1000));
  }

  async increment(key: string): Promise<IncrementResponse> {
    const fullKey = `${this.prefix}${key}`;
    const result = (await this.client
      .multi()
      .call('INCR', fullKey)
      .call('EXPIRE', fullKey, String(this.windowSec), 'NX')
      .call('TTL', fullKey)
      .exec()) as Array<[Error | null, unknown]> | null;

    if (!result || result.length < 3) {
      throw new Error('rate-limit MULTI returned null or short result (transaction aborted)');
    }

    const [[, hitsRaw], , [, ttlRaw]] = result;
    const totalHits = Number(hitsRaw);
    const ttlSec = Number(ttlRaw);
    return {
      totalHits,
      resetTime: new Date(Date.now() + Math.max(0, ttlSec) * 1000),
    };
  }

  async decrement(key: string): Promise<void> {
    await this.client.call('DECR', `${this.prefix}${key}`);
  }

  async resetKey(key: string): Promise<void> {
    await this.client.call('DEL', `${this.prefix}${key}`);
  }

  async get(key: string): Promise<ClientRateLimitInfo | undefined> {
    const fullKey = `${this.prefix}${key}`;
    const result = (await this.client.multi().call('GET', fullKey).call('TTL', fullKey).exec()) as Array<
      [Error | null, unknown]
    > | null;
    if (!result) return undefined;
    const [[, hitsRaw], [, ttlRaw]] = result;
    if (hitsRaw == null) return undefined;
    const ttlSec = Number(ttlRaw);
    return {
      totalHits: Number(hitsRaw),
      resetTime: new Date(Date.now() + Math.max(0, ttlSec) * 1000),
    };
  }
}

const projectPrefix = process.env.REDIS_PREFIX || process.env.PROJECT_ID || 'rl';
const prefix = `${projectPrefix}:global:`;

/**
 * Rate-limit bucketing key. Exported for unit tests.
 *
 * This limiter is mounted BEFORE auth middleware (app.ts), so req.user is
 * never populated here. Every install of this app ships the SAME baked-in
 * PROJECT_API_KEY, so keying on the api key alone used to collapse ALL of
 * the app's end-users worldwide into ONE shared bucket — app-wide self-DoS
 * at trivial scale.
 *
 * 1. Per-user: decode (NOT verify) the JWT and key on its userId claim (the
 *    shape generateAccessToken signs; `sub` accepted for robustness).
 *    Decode-only is safe for BUCKETING: forging a userId only splits the
 *    forger's own traffic across buckets, each still individually capped,
 *    and invalid/absent tokens fall through to the per-IP fallbacks.
 *    Defensive by contract — a malformed token from an untrusted client must
 *    NEVER throw here (that would 500 every anonymous request).
 * 2. Anonymous with API key (login/register/public reads): apikey+ip. The
 *    platform proxy forwards X-Forwarded-For and app.ts sets `trust proxy`,
 *    so req.ip is the real client address — per end-user device, not one
 *    shared bucket.
 * 3. Bare fallback: per-IP.
 */
export function rateLimitKeyGenerator(req: {
  headers: Record<string, string | string[] | undefined>;
  ip?: string;
}): string {
  const auth = req.headers.authorization;
  if (typeof auth === 'string' && auth.startsWith('Bearer ')) {
    try {
      const payload = jwt.decode(auth.slice('Bearer '.length));
      if (payload && typeof payload === 'object') {
        const uid = (payload as jwt.JwtPayload).userId ?? (payload as jwt.JwtPayload).sub;
        if (typeof uid === 'string' && uid.length > 0) {
          return `user:${uid}`;
        }
      }
    } catch {
      /* malformed token — fall through to the anonymous keys */
    }
  }

  const apiKey = req.headers['x-api-key'];
  if (apiKey && typeof apiKey === 'string') {
    return `apikey:${apiKey}:ip:${req.ip || 'unknown'}`;
  }

  return `ip:${req.ip || 'unknown'}`;
}

export const globalRateLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  // Per-IDENTIFIER budget. Identifiers are per end-user (JWT userId) or
  // per client IP (anonymous) — never one shared app-wide bucket, see
  // rateLimitKeyGenerator. Generous by design: this exists to stop abuse,
  // not to throttle an active legitimate session.
  max: 300,
  standardHeaders: true, // Return rate limit info in the `RateLimit-*` headers
  legacyHeaders: false, // Disable the `X-RateLimit-*` headers
  passOnStoreError: true, // If Redis is down, allow requests rather than crashing the app
  keyGenerator: rateLimitKeyGenerator,
  // If Redis is configured, use it. Otherwise, express-rate-limit falls back to its built-in MemoryStore.
  ...(redisClient
    ? {
        store: new IncrExpireStore(redisClient, prefix),
      }
    : {}),
});
