import { Request, Response, NextFunction } from 'express';
import qs from 'qs';

import { logger } from '../lib/logger';
import { getRedis } from '../lib/redis';

/**
 * Redis-backed GET response cache. Mount it in front of heavy list /
 * aggregation endpoints:
 *
 * ```ts
 * router.get('/items', authMiddleware, cache({ ttl: 60, userScoped: true }), handler);
 * ```
 *
 * Key shape: `cache:<method>:<namespace>:<userId|public>:<path>?<query>`
 *
 * Writes that invalidate the cache should call `invalidateCache('items:*')`
 * from the route handler AFTER the DB write succeeds. Invalidation uses a
 * prefix wildcard — be generous so stale lists never linger.
 *
 * The middleware auto-skips when:
 *  - Redis isn't reachable (graceful — never fails the request)
 *  - `req.method` is not `GET`
 *  - the response status is not 2xx (don't cache errors)
 *  - `res.locals.noCache === true` (handler opt-out)
 */
export interface CacheOptions {
  /** Cache lifetime in seconds. */
  ttl?: number;
  /** Include the authenticated user id in the cache key — prevents one user seeing another's data. Default: true. */
  userScoped?: boolean;
  /** Logical bucket for invalidation. Use e.g. `'workouts'` then call `invalidateCache('workouts:*')` after POST/PUT/DELETE. Defaults to the route path. */
  namespace?: string;
}

function buildKey(req: Request, namespace: string, userScoped: boolean): string {
  const userId = (req as Request & { user?: { id?: string } }).user?.id || 'public';
  const scope = userScoped ? userId : 'public';
  // `qs.stringify` mirrors Express's `qs`-based query parser so nested
  // objects, arrays, and non-string values round-trip correctly. Sorted
  // keys make `?a=1&b=2` and `?b=2&a=1` share a single cache entry.
  const queryString = qs.stringify(req.query as Record<string, unknown>, {
    sort: (a, b) => a.localeCompare(b),
    encode: true,
  });
  return `cache:GET:${namespace}:${scope}:${req.path}${queryString ? '?' + queryString : ''}`;
}

export function cache(options: CacheOptions = {}) {
  const ttl = options.ttl ?? 60;
  const userScoped = options.userScoped ?? true;

  return async function cacheMiddleware(req: Request, res: Response, next: NextFunction): Promise<void> {
    if (req.method !== 'GET') {
      next();
      return;
    }

    const redis = getRedis();
    if (!redis) {
      next();
      return;
    }

    const namespace = (options.namespace ?? req.baseUrl.replace(/^\/+|\/+$/g, '')) || 'root';
    const key = buildKey(req, namespace, userScoped);

    try {
      const hit = await redis.get(key);
      if (hit) {
        const parsed = JSON.parse(hit) as { status: number; body: unknown };
        res.status(parsed.status).setHeader('X-Cache', 'HIT').json(parsed.body);
        return;
      }
    } catch (err) {
      logger.warn({ err, key }, '[cache] lookup failed — serving live');
    }

    const originalJson = res.json.bind(res);
    res.json = (body: unknown) => {
      if (res.statusCode >= 200 && res.statusCode < 300 && res.locals.noCache !== true) {
        redis
          .set(key, JSON.stringify({ status: res.statusCode, body }), 'EX', ttl)
          .catch((err) => logger.warn({ err, key }, '[cache] set failed'));
      }
      res.setHeader('X-Cache', 'MISS');
      return originalJson(body);
    };

    next();
  };
}

/**
 * Invalidate every key in a namespace via prefix-wildcard. Call from POST /
 * PUT / DELETE handlers AFTER the DB mutation commits.
 *
 * ```ts
 * await prisma.workout.create(...);
 * await invalidateCache('workouts:*');
 * ```
 */
export async function invalidateCache(pattern: string): Promise<number> {
  const redis = getRedis();
  if (!redis) return 0;
  const fullPattern = pattern.startsWith('cache:') ? pattern : `cache:GET:${pattern}`;
  let cursor = '0';
  let removed = 0;
  try {
    do {
      const [next, keys] = await redis.scan(cursor, 'MATCH', fullPattern, 'COUNT', 200);
      cursor = next;
      if (keys.length > 0) {
        await redis.del(...keys);
        removed += keys.length;
      }
    } while (cursor !== '0');
  } catch (err) {
    logger.warn({ err, pattern }, '[cache] invalidate failed');
  }
  return removed;
}
