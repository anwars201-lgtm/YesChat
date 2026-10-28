import { beforeEach, describe, expect, it, vi } from 'vitest';

const dependencies = vi.hoisted(() => ({ dbUp: true, redisUp: true, redisConfigured: true }));

vi.mock('../../lib/prisma', () => ({
  prisma: {
    $queryRaw: vi.fn(async () => {
      if (!dependencies.dbUp) throw new Error('database unavailable');
      return [{ ok: 1 }];
    }),
  },
}));

vi.mock('../../lib/redis', () => ({
  getRedis: () =>
    dependencies.redisConfigured
      ? {
          ping: vi.fn(async () => {
            if (!dependencies.redisUp) throw new Error('redis unavailable');
            return 'PONG';
          }),
        }
      : null,
}));

import healthRouter from '../health.routes';

interface RouterLayer {
  route?: {
    path: string;
    stack: Array<{ handle: (req: unknown, res: unknown) => unknown }>;
  };
}

async function invokeRoute(path: '/' | '/ready' | '/live'): Promise<{ status: number; body: Record<string, unknown> }> {
  const stack = (healthRouter as unknown as { stack: RouterLayer[] }).stack;
  const handler = stack.find((layer) => layer.route?.path === path)?.route?.stack[0]?.handle;
  if (!handler) throw new Error(`Missing health route ${path}`);
  const result = { status: 0, body: {} as Record<string, unknown> };
  const response = {
    status(code: number) {
      result.status = code;
      return response;
    },
    json(body: Record<string, unknown>) {
      result.body = body;
      return response;
    },
  };
  await handler({}, response);
  return result;
}

describe('generated backend health contract', () => {
  beforeEach(() => {
    dependencies.dbUp = true;
    dependencies.redisUp = true;
    dependencies.redisConfigured = true;
  });

  it.each(['/' as const, '/ready' as const])('%s is ready only when DB and Redis both answer', async (path) => {
    const response = await invokeRoute(path);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ status: 'ok', db: 'ok', redis: 'ok' });
  });

  it('returns 503 when the frozen Redis credential is invalid or unavailable', async () => {
    dependencies.redisUp = false;

    const response = await invokeRoute('/ready');

    expect(response.status).toBe(503);
    expect(response.body).toMatchObject({ status: 'down', db: 'ok', redis: 'down' });
  });

  it('returns 503 when Redis is not configured instead of silently disabling readiness', async () => {
    dependencies.redisConfigured = false;

    const response = await invokeRoute('/');

    expect(response.status).toBe(503);
    expect(response.body).toMatchObject({ status: 'down', db: 'ok', redis: 'down' });
  });

  it('returns 503 when the database is unavailable', async () => {
    dependencies.dbUp = false;

    const response = await invokeRoute('/');

    expect(response.status).toBe(503);
    expect(response.body).toMatchObject({ status: 'down', db: 'down', redis: 'ok' });
  });

  it('keeps liveness dependency-free to avoid restart storms', async () => {
    dependencies.dbUp = false;
    dependencies.redisUp = false;

    const response = await invokeRoute('/live');

    expect(response.status).toBe(200);
    expect(response.body.status).toBe('ok');
    expect(response.body).not.toHaveProperty('db');
    expect(response.body).not.toHaveProperty('redis');
  });
});
