import { Router, type Request, type Response } from 'express';

import { prisma } from '../lib/prisma';
import { getRedis } from '../lib/redis';

const router = Router();

const BOOT_TIME_MS = Date.now();
const PACKAGE_VERSION = process.env.BACKEND_VERSION || 'unknown';

type HealthStatus = 'ok' | 'down';

async function checkDb(): Promise<HealthStatus> {
  try {
    await prisma.$queryRaw`SELECT 1`;
    return 'ok';
  } catch {
    return 'down';
  }
}

async function checkRedis(): Promise<HealthStatus> {
  const redis = getRedis();
  if (!redis) return 'down';
  try {
    const pong = await redis.ping();
    return pong === 'PONG' ? 'ok' : 'down';
  } catch {
    return 'down';
  }
}

/**
 * GET /health and GET /health/ready
 *
 * Readiness requires every dependency the generated application relies on.
 * Returning HTTP 200 with `redis:"down"` made a container look deployable
 * while auth rate limits, cache and realtime were unusable.
 */
async function readiness(_req: Request, res: Response) {
  const [db, redisStatus] = await Promise.all([checkDb(), checkRedis()]);
  const uptimeMs = Date.now() - BOOT_TIME_MS;
  const overall: HealthStatus = db === 'ok' && redisStatus === 'ok' ? 'ok' : 'down';
  res.status(overall === 'ok' ? 200 : 503).json({
    status: overall,
    db,
    redis: redisStatus,
    uptimeMs,
    version: PACKAGE_VERSION,
    timestamp: new Date().toISOString(),
  });
}

router.get('/', readiness);
router.get('/ready', readiness);

/** Process liveness is intentionally dependency-free. Orchestrators can use
 * this to decide whether to restart the Node process without turning a brief
 * database/Redis outage into a restart storm. */
router.get('/live', (_req, res) => {
  res.status(200).json({
    status: 'ok',
    uptimeMs: Date.now() - BOOT_TIME_MS,
    version: PACKAGE_VERSION,
    timestamp: new Date().toISOString(),
  });
});

export default router;
