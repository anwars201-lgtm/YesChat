import 'express-async-errors';
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import healthRoutes from './routes/health.routes';
import authRoutes from './routes/auth.routes';
import uploadRoutes from './routes/upload.routes';
import { apiKeyMiddleware } from './middleware/apiKey.middleware';
import { authMiddleware } from './middleware/auth.middleware';
import { errorMiddleware } from './middleware/error.middleware';
import { globalRateLimiter } from './middleware/rateLimit.middleware';
import { requestIdMiddleware } from './middleware/request-id.middleware';
import { registerSSERoute, sseChannelOwnershipGuard } from './lib/realtime';
import { logger } from './lib/logger';
import { mountGeneratedAppRoutes } from './generated/app-routes';

const app = express();

// The container runs behind AppMuse's platform-api proxy, so the raw socket
// peer is always the proxy — not the end user. Trust one proxy hop so
// `req.ip` resolves from X-Forwarded-For. Without this the auth rate-limiter
// and login-lockout (which key off `req.ip`) bucket every user together
// instead of per-client, defeating brute-force protection.
app.set('trust proxy', 1);

app.use(helmet());

// CORS — supports comma-separated allow-list (`https://app.com,https://staging.app.com`).
// TMPL-6b: `*` (any web origin) is now an EXPLICIT opt-in — set `CORS_ORIGIN=*`
// to enable it. When CORS_ORIGIN is unset we default to NO cross-origin
// (origin: false) instead of the old silent `*`, which quietly let any browser
// origin call the API. Native/mobile clients ignore CORS entirely, so the
// hosted data path is unaffected; only browser frontends need CORS_ORIGIN set
// to their own origin. Never boot-throws — a misconfig degrades to
// same-origin-only, not a crash.
const rawCorsOrigin = process.env.CORS_ORIGIN ?? '';
const allowedOrigins = rawCorsOrigin
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);
const corsWildcard = allowedOrigins.includes('*');
if (process.env.NODE_ENV === 'production' && corsWildcard) {
  logger.warn(
    '[security] CORS_ORIGIN="*" in production — any web origin can call this API. Mobile clients ignore CORS so this only matters for browser frontends.',
  );
}
// [] (unset) -> false (no cross-origin); ['*'] -> '*'; one -> string; many -> array.
const corsOriginValue: boolean | string | string[] = corsWildcard
  ? '*'
  : allowedOrigins.length === 0
    ? false
    : allowedOrigins.length === 1
      ? allowedOrigins[0]
      : allowedOrigins;
app.use(
  cors({
    origin: corsOriginValue,
    credentials: corsOriginValue !== '*' && corsOriginValue !== false ? true : undefined,
    exposedHeaders: ['X-Request-Id'],
  }),
);
// Request-id + pino HTTP logging BEFORE anything that may log so every
// entry gets the correlation id attached. See middleware/request-id.middleware.ts.
app.use(requestIdMiddleware);
// Body limit raised from express's default 100kb to 1MB so base64-inline
// payloads (avatar in /auth/register, push-token registration, small
// document uploads) work without 413. Multipart file uploads still go
// through /upload (multer has its own limits.fileSize). Override via
// BODY_LIMIT envvar when an app legitimately needs more.
app.use(express.json({ limit: process.env.BODY_LIMIT || '1mb' }));
app.use('/uploads', express.static(process.env.UPLOAD_DIR || '/workspace/uploads'));

// Health check (no API key required, excluded from rate limiting)
app.use('/health', healthRoutes);

// Apply global rate limiting to all other routes
app.use(globalRateLimiter);

// API key validation for all routes below
app.use(apiKeyMiddleware);

// Auth routes — mounted in EVERY mode (ADR-0046, 2026-09-14). `required` and
// `optional` need them (`optional` applies authMiddleware per route, not
// globally); `none` (utility / offline-only apps) needs the session endpoints
// too: every generated feature route requires a bearer token, so the app opens
// a per-device guest session through `POST /auth/guest` (answered only when
// APP_AUTH=none, see auth.routes.ts) and refreshes it like any other session.
// The mount used to be skipped for `none`, which also 404'd `/auth/register`
// for the template + generated backend tests and failed every no-auth
// foundation verify.
const authMode = process.env.APP_AUTH || 'required';
app.locals.authMode = authMode;
app.use('/auth', authRoutes);

// Upload routes — opt-out via ENABLE_UPLOADS=false for apps without
// user-generated file uploads (utilities, games, pure readers).
if (process.env.ENABLE_UPLOADS !== 'false') {
  app.use('/upload', uploadRoutes);
}

// Realtime (SSE) — opt-in via ENABLE_REALTIME=true. Primary use is the
// chat-messaging archetype; feature screens publish messages via
// `sseBroadcast()` and clients subscribe at `/events/:channel`.
// TMPL-6a: SSE user-auth is ON by default (opt OUT with SSE_REQUIRE_AUTH=false).
// authMiddleware requires a valid JWT and sseChannelOwnershipGuard blocks
// subscribing to another user's `user:<id>` channel (conversation:<id> stays
// allowed for any authenticated user). Clients therefore MUST open the event
// stream with the same `X-API-Key` + `Authorization: Bearer <token>` they send
// on every other request — the generated Flutter/Kotlin/Swift SSE clients do
// this (see the stack feature-procedures prompts). Set SSE_REQUIRE_AUTH=false
// only for a legacy app whose SSE client cannot yet send the token.
// (TMPL-6h: top-level import, not an inline require().)
if (process.env.ENABLE_REALTIME === 'true') {
  const ssePreHandlers = process.env.SSE_REQUIRE_AUTH !== 'false' ? [authMiddleware, sseChannelOwnershipGuard] : [];
  registerSSERoute(app, '/events/:channel', ssePreHandlers);
}

// AI: Mount your app-specific routes below
mountGeneratedAppRoutes(app);

// Error handler (must be last)
app.use(errorMiddleware);

export default app;
