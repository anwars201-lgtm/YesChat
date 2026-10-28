import { getRedis } from './redis';

const MAX_FAILED_ATTEMPTS = 5;
const ATTEMPT_WINDOW_SECONDS = 30 * 60;
const LOCKOUT_DURATION_SECONDS = 30 * 60;

function key(email: string, kind: 'attempts' | 'lockout'): string {
  return `login:${kind}:${email.toLowerCase().trim()}`;
}

/**
 * Increment the failed-login counter for this email. If the counter
 * crosses MAX_FAILED_ATTEMPTS within the rolling window, set a lockout
 * marker that blocks subsequent attempts for LOCKOUT_DURATION_SECONDS.
 *
 * Graceful degradation: when Redis is unreachable, this is a no-op —
 * better to let users in than to fail-closed and lock everyone out
 * because Redis blipped. The per-IP rate limiter still applies.
 */
export async function recordFailedLogin(email: string): Promise<void> {
  const r = getRedis();
  if (!r) return;
  try {
    const attemptsKey = key(email, 'attempts');
    // Atomic incr+expire via pipeline so a process crash between the two
    // commands can't leave a TTL-less counter (which would lock the user
    // forever until the key was manually cleared). We always re-set the
    // TTL on every increment — the rolling window resets on each failure,
    // which is the standard threat-model behaviour for brute-force
    // protection.
    const results = await r.multi().incr(attemptsKey).expire(attemptsKey, ATTEMPT_WINDOW_SECONDS).exec();
    if (!results) return;
    const count = Number(results[0]?.[1] ?? 0);
    if (count >= MAX_FAILED_ATTEMPTS) {
      await r.set(key(email, 'lockout'), '1', 'EX', LOCKOUT_DURATION_SECONDS);
    }
  } catch {
    // swallow — fail-open is the right policy here
  }
}

/**
 * Returns the remaining lockout duration in seconds for this email,
 * or null if the account is not locked. Use to short-circuit the
 * password-check (saves a bcrypt + DB read on locked accounts and
 * makes the lockout effective).
 */
export async function getLockoutTtl(email: string): Promise<number | null> {
  const r = getRedis();
  if (!r) return null;
  try {
    const ttl = await r.ttl(key(email, 'lockout'));
    return ttl > 0 ? ttl : null;
  } catch {
    return null;
  }
}

/**
 * Clear failed-attempt + lockout markers after a successful login.
 * Without this, a user who fails 4 times then succeeds would still
 * be one fail away from lockout for the rest of the window.
 */
export async function clearFailedAttempts(email: string): Promise<void> {
  const r = getRedis();
  if (!r) return;
  try {
    await Promise.all([r.del(key(email, 'attempts')), r.del(key(email, 'lockout'))]);
  } catch {
    // best-effort
  }
}
