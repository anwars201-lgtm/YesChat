import { Request, Response, NextFunction } from 'express';
import crypto from 'crypto';

// TMPL-6c: constant-time API-key comparison. A plain `!==` returns as soon as
// the first differing byte is found, leaking through timing how many leading
// bytes matched — enough to recover the key byte-by-byte. crypto.timingSafeEqual
// needs equal-length buffers (it throws otherwise), so we compare fixed-length
// SHA-256 digests: hashing both equalises length AND removes any data-dependent
// branch on the raw key. Standalone template — no monorepo import.
function timingSafeEqualStr(a: string, b: string): boolean {
  const ha = crypto.createHash('sha256').update(a).digest();
  const hb = crypto.createHash('sha256').update(b).digest();
  return crypto.timingSafeEqual(ha, hb);
}

export function apiKeyMiddleware(req: Request, res: Response, next: NextFunction): void {
  const apiKey = process.env.PROJECT_API_KEY;

  // If no API key is configured, skip validation
  if (!apiKey) {
    next();
    return;
  }

  const providedKey = req.headers['x-api-key'];

  // typeof guard: `x-api-key` can be string | string[] | undefined. Only a
  // single string can be a valid key; anything else is rejected before the
  // constant-time compare (which requires a string).
  if (typeof providedKey !== 'string' || !timingSafeEqualStr(providedKey, apiKey)) {
    res.status(401).json({ error: 'Invalid or missing API key' });
    return;
  }

  next();
}
