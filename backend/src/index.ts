import dotenv from 'dotenv';
dotenv.config();

import app from './app';
import { prisma } from './lib/prisma';
import { logger } from './lib/logger';
import { startSessionSweep } from './lib/session-sweep';

const PORT = parseInt(process.env.PORT || '3000', 10);
// Default 8s so the drain finishes well before Docker's default 10s
// SIGTERM→SIGKILL grace. Operators who set `docker stop --time=30` can
// raise SHUTDOWN_GRACE_MS to match without code changes.
const SHUTDOWN_GRACE_MS = parseInt(process.env.SHUTDOWN_GRACE_MS || '8000', 10);

const server = app.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`Backend running on http://localhost:${PORT}`);
});

// TMPL-6f: periodically purge expired sessions (unref'd — never blocks exit).
startSessionSweep();

let shuttingDown = false;
async function shutdown(signal: string) {
  if (shuttingDown) return;
  shuttingDown = true;
  logger.info({ signal, graceMs: SHUTDOWN_GRACE_MS }, 'shutdown signal received — draining');

  const forceKill = setTimeout(() => {
    logger.warn('shutdown drain exceeded grace — forcing exit');
    process.exit(1);
  }, SHUTDOWN_GRACE_MS);
  forceKill.unref();

  // server.close fires the callback once all in-flight requests have drained.
  // We MUST await this before disconnecting Prisma — otherwise an in-flight
  // request loses its DB connection mid-query and the user sees a 500.
  await new Promise<void>((resolve) => {
    server.close((err) => {
      if (err) logger.warn({ err }, 'http server close error');
      resolve();
    });
  });

  try {
    await prisma.$disconnect();
  } catch (err) {
    logger.warn({ err }, 'prisma disconnect error');
  }

  clearTimeout(forceKill);
  logger.info('shutdown complete');
  process.exit(0);
}

process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));
