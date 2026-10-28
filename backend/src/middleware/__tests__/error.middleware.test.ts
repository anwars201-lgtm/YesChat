// A zod `schema.parse()` failure (e.g. a create with a missing required field)
// must surface as a 400 VALIDATION_ERROR with the offending field paths — NOT
// the generic 500 INTERNAL_ERROR that leaked before (a real user hitting an
// incomplete request got "Something went wrong" with no clue what was wrong).

import { describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import type { Request, Response, NextFunction } from 'express';

import { AppError, errorMiddleware } from '../error.middleware';

function mockRes() {
  const res: Partial<Response> & { _status?: number; _body?: unknown } = {};
  res.status = vi.fn((code: number) => {
    res._status = code;
    return res as Response;
  }) as unknown as Response['status'];
  res.json = vi.fn((body: unknown) => {
    res._body = body;
    return res as Response;
  }) as unknown as Response['json'];
  return res as Response & { _status?: number; _body?: { error?: string; code?: string; fields?: string[] } };
}

const req = { method: 'POST', originalUrl: '/traningspass' } as unknown as Request;
const next = (() => undefined) as NextFunction;

function zodErrorFrom(shape: z.ZodTypeAny, value: unknown): Error {
  const r = shape.safeParse(value);
  if (r.success) throw new Error('expected a validation failure');
  return r.error;
}

describe('errorMiddleware — zod validation → 400', () => {
  it('maps a ZodError to 400 VALIDATION_ERROR with the missing field path', () => {
    const schema = z.object({ name: z.string(), durationMinutes: z.number() });
    const err = zodErrorFrom(schema, { name: 'x' }); // durationMinutes missing
    const res = mockRes();
    errorMiddleware(err, req, res, next);
    expect(res._status).toBe(400);
    expect(res._body?.code).toBe('VALIDATION_ERROR');
    expect(res._body?.fields).toContain('durationMinutes');
  });

  it('reports nested field paths (e.g. a[0].b)', () => {
    const schema = z.object({ items: z.array(z.object({ qty: z.number() })) });
    const err = zodErrorFrom(schema, { items: [{ qty: 'no' }] });
    const res = mockRes();
    errorMiddleware(err, req, res, next);
    expect(res._status).toBe(400);
    expect(res._body?.fields?.some((f) => f.includes('items'))).toBe(true);
  });

  it('still returns 500 INTERNAL_ERROR for a genuine unexpected error (regression)', () => {
    const res = mockRes();
    errorMiddleware(new Error('boom'), req, res, next);
    expect(res._status).toBe(500);
    expect(res._body?.code).toBe('INTERNAL_ERROR');
  });

  it('AppError is still honored (regression)', () => {
    const res = mockRes();
    errorMiddleware(new AppError(403, 'nope', 'FORBIDDEN'), req, res, next);
    expect(res._status).toBe(403);
    expect(res._body?.code).toBe('FORBIDDEN');
  });
});
