// Vitest globalSetup — runs ONCE before any test file is imported.
//
// Critical: src/lib/prisma.ts captures DATABASE_URL at module load time
// (PrismaClient is constructed eagerly), so we have to inject the unique
// per-run schema HERE — before any test file imports `app` or `prisma`.
// Mutating process.env in a regular `beforeAll` is too late: the modules
// have already loaded with the old URL.
//
// Sets a single env var consumed by setup.ts: APPBUILDER_TEST_SCHEMA.

import { execSync } from 'child_process';

function testRunId(): string {
  return `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
}

export default async function globalSetup() {
  if (!process.env.DATABASE_URL) {
    throw new Error('DATABASE_URL must be set to a Postgres test database before running tests.');
  }

  const schema = `test_${Math.random().toString(36).slice(2, 10)}`;
  const url = new URL(process.env.DATABASE_URL);
  url.searchParams.set('schema', schema);
  process.env.DATABASE_URL = url.toString();
  process.env.APPBUILDER_TEST_SCHEMA = schema;
  process.env.JWT_SECRET = process.env.JWT_SECRET || 'test-jwt-secret-please-override-in-real-deploys';

  // Redis state is just as test-sensitive as Postgres state (account lockouts,
  // rate limits, cache and realtime channels). Reusing the project's normal
  // prefix made a second suite inherit the first suite's lockout counters.
  // Keep the platform-provisioned tenant prefix as the outer namespace so the
  // customer Redis ACL remains valid, then add a unique per-suite suffix.
  const tenantPrefix = process.env.REDIS_PREFIX || process.env.PROJECT_ID;
  if (process.env.REDIS_URL && !tenantPrefix) {
    throw new Error('REDIS_PREFIX or PROJECT_ID is required when Redis-backed tests are enabled.');
  }
  const redisTestPrefix = tenantPrefix ? `${tenantPrefix}:test:${testRunId()}` : undefined;
  if (redisTestPrefix) {
    process.env.REDIS_PREFIX = redisTestPrefix;
    process.env.APPBUILDER_TEST_REDIS_PREFIX = redisTestPrefix;
  }

  execSync('npx prisma db push --skip-generate --accept-data-loss', {
    stdio: 'inherit',
    env: process.env,
  });

  return async () => {
    // teardown — drop the schema. Use a one-shot Prisma client so we don't
    // pull the test-suite client (which may already be disconnected).
    const { PrismaClient } = await import('@prisma/client');
    const client = new PrismaClient();
    try {
      await client.$executeRawUnsafe(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    } catch {
      /* best-effort */
    }
    await client.$disconnect();

    // Best-effort cleanup using a client WITHOUT keyPrefix. SCAN is bounded to
    // this run's tenant-qualified namespace; UNLINK avoids blocking Redis when
    // a suite created large cache entries. TTLs remain the safety net if the
    // process is killed before teardown.
    if (process.env.REDIS_URL && redisTestPrefix) {
      const { default: Redis } = await import('ioredis');
      const redis = new Redis(process.env.REDIS_URL, { maxRetriesPerRequest: 1, lazyConnect: true });
      try {
        await redis.connect();
        let cursor = '0';
        do {
          const [next, keys] = await redis.scan(cursor, 'MATCH', `${redisTestPrefix}:*`, 'COUNT', 200);
          cursor = next;
          if (keys.length > 0) await redis.unlink(...keys);
        } while (cursor !== '0');
      } catch {
        /* best-effort; rate-limit and lockout keys also expire */
      } finally {
        redis.disconnect();
      }
    }
  };
}
