import { Request, Response, NextFunction } from 'express';
import { ZodSchema } from 'zod';

/**
 * Zod request-validation middleware.
 *
 * Validates `req.body` / `req.query` / `req.params` against the supplied schemas
 * (pass any subset). On the FIRST failure it responds
 *   400 { error: 'Validation failed', details: [{ path, message }] }
 * — the exact shape the apps map to a validation error — and does NOT call
 * `next()`. On success it writes the parsed (coerced/stripped) body back onto the
 * request so handlers read typed data, then calls `next()`.
 *
 * Usage:
 *   router.post('/items', authMiddleware, validate({ body: createItemSchema }), handler)
 *
 * Inline `schema.safeParse(req.body)` (as used by the auth routes) remains valid;
 * this middleware is the reusable equivalent for feature routes.
 */
export interface ValidateSchemas {
  body?: ZodSchema;
  query?: ZodSchema;
  params?: ZodSchema;
}

export function validate(schemas: ValidateSchemas) {
  return (req: Request, res: Response, next: NextFunction): void => {
    for (const key of ['body', 'query', 'params'] as const) {
      const schema = schemas[key];
      if (!schema) continue;
      const result = schema.safeParse(req[key]);
      if (!result.success) {
        res.status(400).json({
          error: 'Validation failed',
          details: result.error.issues.map((issue) => ({
            path: issue.path.join('.'),
            message: issue.message,
          })),
        });
        return;
      }
      // Only req.body is safe to reassign across Express versions; query/params
      // are validated (gated) but left as Express parsed them.
      if (key === 'body') req.body = result.data;
    }
    next();
  };
}
