import { randomUUID } from 'node:crypto';
import { NextFunction, Request, Response } from 'express';
import pinoHttp from 'pino-http';

import { logger } from '../lib/logger';

declare module 'express-serve-static-core' {
  interface Request {
    id: string;
    log: typeof logger;
  }
}

/**
 * Accept an existing `X-Request-Id` from the upstream (platform-api proxy
 * injects one for every incoming request) and reuse it end-to-end.
 * Falls back to a fresh UUID when the header is missing (local dev,
 * direct-to-backend tests). The id is:
 *   - echoed back in the response as `X-Request-Id` so the Flutter app
 *     can show it in support dialogs and users can correlate their
 *     failure against server logs.
 *   - attached to every `req.log` call via pino-http.
 */
export const requestIdMiddleware = [
  (req: Request, res: Response, next: NextFunction): void => {
    const incoming = req.header('x-request-id');
    const id = incoming && /^[a-zA-Z0-9_-]{6,128}$/.test(incoming) ? incoming : randomUUID();
    req.id = id;
    res.setHeader('X-Request-Id', id);
    next();
  },
  pinoHttp({
    logger,
    genReqId: (req) => (req as Request).id,
    customProps: (req) => ({ requestId: (req as Request).id }),
    // Don't log health probes at info level — they fire every few seconds
    // and would drown out real traffic.
    customLogLevel: (req, res, err) => {
      if (err || res.statusCode >= 500) return 'error';
      if (res.statusCode >= 400) return 'warn';
      if (req.url?.startsWith('/health')) return 'debug';
      return 'info';
    },
    serializers: {
      req: (req) => ({
        method: req.method,
        url: req.url,
        remoteAddress: req.remoteAddress,
      }),
      res: (res) => ({
        statusCode: res.statusCode,
      }),
    },
  }),
];
