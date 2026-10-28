/**
 * B-38: backend smoke-test scaffold.
 *
 * Enumerates every GET-route registered on the Express app and asserts
 * the response is NOT a 5xx error. The point is to catch routes that
 * crash before reaching their handler — typically caused by:
 *
 *   - a route handler throwing on import (bad require / missing env)
 *   - a middleware ordering bug (e.g. apiKey middleware reading body
 *     before json-parser ran)
 *   - a missing Prisma model reference (route compiles but throws when
 *     the resolver actually runs)
 *   - a registered plugin throwing on a synthetic id (e.g. a UUID
 *     coercion that crashes on '00000000-...')
 *
 * It does NOT assert correctness — a 404, 400, 401, 403 are all fine
 * here. The smoke is purely "no 500". When this test fails, the agent
 * should read the failing route handler and find the source of the
 * crash before continuing to add features.
 *
 * Authentication: every request carries a valid API key + a bearer
 * token signed by `signSyntheticAccessToken` (test-only helper). Routes
 * that explicitly require admin / role-gated access will return 403 —
 * still non-500 and therefore green.
 *
 * Adding/removing routes does NOT require updating this test — the
 * enumerator walks the live Express router stack on every run.
 */

import { describe, expect, it } from 'vitest';
import request from 'supertest';
import jwt from 'jsonwebtoken';

import app from '../src/app';

interface ExpressLayer {
  route?: { path: string; methods: Record<string, boolean>; stack: ExpressLayer[] };
  name?: string;
  regexp?: RegExp;
  handle?: { stack?: ExpressLayer[] };
}

const PARAM_SUBSTITUTION = '00000000-0000-0000-0000-000000000000';

// Routes we deliberately skip from the smoke check, with reasoning.
// Add to this list with care — every skip is a route the smoke can't
// protect.
const SMOKE_SKIP_PATHS: ReadonlyArray<{ pattern: RegExp; reason: string }> = [
  { pattern: /^\/uploads\b/, reason: 'static file serving — express.static, not a route handler' },
  { pattern: /^\/events\//, reason: 'SSE long-polling — response stays open' },
];

function shouldSkip(routePath: string): { skip: boolean; reason?: string } {
  for (const entry of SMOKE_SKIP_PATHS) {
    if (entry.pattern.test(routePath)) return { skip: true, reason: entry.reason };
  }
  return { skip: false };
}

/**
 * Substitute `:param` segments with a synthetic UUID so the URL is
 * parseable. Handlers that coerce to bigint / int from the segment may
 * still 400 — that's fine; we only care about non-500.
 */
function instantiatePath(routePath: string): string {
  return routePath.replace(/:[A-Za-z_]\w*/g, PARAM_SUBSTITUTION);
}

/**
 * Walk Express' router stack (recursively through nested routers,
 * e.g. routes mounted under `/auth`). Returns the list of GET routes.
 */
function collectGetRoutes(expressApp: typeof app): string[] {
  const out: string[] = [];

  function walk(stack: ExpressLayer[], prefix: string): void {
    for (const layer of stack) {
      if (layer.route) {
        const isGet = layer.route.methods.get === true;
        if (!isGet) continue;
        out.push(prefix + layer.route.path);
        continue;
      }
      // Nested router (app.use('/auth', router))
      if (layer.name === 'router' && layer.handle && Array.isArray(layer.handle.stack)) {
        const nestedPrefix = extractMountPath(layer);
        walk(layer.handle.stack, prefix + nestedPrefix);
      }
    }
  }

  function extractMountPath(layer: ExpressLayer): string {
    // Express 4 mount-path recovery (in priority order):
    //  1. `layer.path` — set on some dev/debug builds. Cheap if present.
    //  2. `layer.regexp.source` — standard Express output. Mount routers
    //     get a regex like `/^\/auth\/?(?=\/|$)/i`. Strip the boilerplate
    //     to recover '/auth'. Root-level (no prefix) gives `/^\/?(?=\/|$)/i`
    //     which our regex does not match — caller falls back to ''.
    //  3. '' — last resort. Means nested routes get walked with no prefix,
    //     producing URLs that 404 instead of hitting the real route. The
    //     smoke would still be green for top-level GETs (e.g. /health).
    const layerWithPath = layer as ExpressLayer & { path?: string };
    if (typeof layerWithPath.path === 'string') return layerWithPath.path;
    if (layer.regexp && typeof layer.regexp.source === 'string') {
      // Match `^\/<segment>(\/<segment>)*\/?(?=\/|$)` and return the
      // un-escaped path with a leading slash.
      const m = layer.regexp.source.match(/^\^\\\/((?:[^/\\?(]|\\\/)+?)\\\/\?\(\?=/);
      if (m) return '/' + m[1].replace(/\\\//g, '/');
    }
    return '';
  }

  // express 4.x exposes the top-level stack at `_router.stack`. Cast
  // through unknown to keep types narrow even though the property is
  // internal API.
  const router = (expressApp as unknown as { _router?: { stack?: ExpressLayer[] } })._router;
  if (router?.stack) walk(router.stack, '');
  return out;
}

/**
 * Synthetic access token. Uses the same JWT secret the app validates
 * against so the auth middleware accepts it. Subject is a UUID that
 * does NOT exist in the DB — handlers that look up the user will 404,
 * which is the expected non-500 outcome.
 */
function signSyntheticAccessToken(): string {
  const secret = process.env.JWT_SECRET || 'dev-secret-change-me';
  return jwt.sign({ sub: PARAM_SUBSTITUTION, role: 'user' }, secret, { expiresIn: '5m' });
}

const apiKey = process.env.PROJECT_API_KEY || 'test-api-key';

describe('backend smoke — every GET returns non-5xx', () => {
  const routes = collectGetRoutes(app);

  it('finds at least one GET route to smoke', () => {
    // If this fails the route-collector is broken — fix the enumerator
    // before trusting the smoke.
    expect(routes.length).toBeGreaterThan(0);
  });

  for (const routePath of routes) {
    const skip = shouldSkip(routePath);
    const concrete = instantiatePath(routePath);
    const label = `GET ${routePath} → non-5xx${skip.skip ? ` (skipped: ${skip.reason})` : ''}`;

    if (skip.skip) {
      it.skip(label, () => undefined);
      continue;
    }

    it(label, async () => {
      const res = await request(app)
        .get(concrete)
        .set('X-Api-Key', apiKey)
        .set('Authorization', `Bearer ${signSyntheticAccessToken()}`);
      expect(res.status).toBeLessThan(500);
    });
  }
});
