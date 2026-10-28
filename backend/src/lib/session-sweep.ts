import { prisma } from './prisma';
import { logger } from './logger';

// TMPL-6f: expired Session rows are never cleaned up on their own — logout and
// "log out everywhere" delete by user/token, but a session that simply expires
// lingers forever, bloating the table (and every userId scan) indefinitely.
// This periodic sweep removes them. Interval is env-tunable; default hourly.
const SWEEP_INTERVAL_MS = parseInt(process.env.SESSION_SWEEP_INTERVAL_MS || String(60 * 60 * 1000), 10);

/** Delete every session whose expiresAt is in the past. Best-effort. */
export async function sweepExpiredSessions(): Promise<number> {
  try {
    const { count } = await prisma.session.deleteMany({ where: { expiresAt: { lt: new Date() } } });
    if (count > 0) logger.info({ count }, '[session-sweep] removed expired sessions');
    return count;
  } catch (err) {
    logger.warn({ err }, '[session-sweep] sweep failed');
    return 0;
  }
}

/**
 * Start the periodic sweep. Runs once shortly after boot, then on the interval.
 * The timer is unref()'d so it never holds the process open during shutdown.
 * Returns the timer so the caller can clearInterval() on shutdown if desired.
 */
export function startSessionSweep(): NodeJS.Timeout {
  void sweepExpiredSessions();
  const timer = setInterval(() => void sweepExpiredSessions(), SWEEP_INTERVAL_MS);
  timer.unref();
  return timer;
}
