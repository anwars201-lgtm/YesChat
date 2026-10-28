import pino from 'pino';

/**
 * Shared pino logger. Writes single-line JSON to stdout so container logs
 * can be parsed by the platform-api's log tailer downstream. Override the
 * level with `LOG_LEVEL` env var (trace/debug/info/warn/error).
 *
 * Each HTTP request gets a child logger with a `requestId` bound — see
 * `middleware/request-id.middleware.ts`. Route handlers should reach for
 * `req.log.info(...)` rather than importing this default logger directly
 * so correlation IDs round-trip automatically.
 */
export const logger = pino({
  level: process.env.LOG_LEVEL || 'info',
  base: {
    service: process.env.BACKEND_SERVICE_NAME || 'backend',
    env: process.env.NODE_ENV || 'development',
  },
  timestamp: pino.stdTimeFunctions.isoTime,
});
