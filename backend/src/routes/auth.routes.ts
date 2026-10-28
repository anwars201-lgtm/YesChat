import { Router, Request, Response } from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import crypto from 'crypto';
import { z } from 'zod';
import { prisma } from '../lib/prisma';
import { authMiddleware } from '../middleware/auth.middleware';
import { AppError } from '../middleware/error.middleware';
import { sendVerificationEmail, sendPasswordResetEmail } from '../lib/email';
import { recordFailedLogin, getLockoutTtl, clearFailedAttempts } from '../lib/account-lockout';
import { logger } from '../lib/logger';
import { getPublicBaseUrl } from '../lib/base-url';

function isEmailVerifyRequired(): boolean {
  return (process.env.REQUIRE_EMAIL_VERIFY ?? 'false').toLowerCase() === 'true';
}

const router = Router();

// Simple in-memory rate limiter for auth endpoints. Budgets are isolated per
// endpoint so registering/resending cannot consume the login safety budget.
const rateLimitStore = new Map<string, { count: number; resetAt: number }>();
export function authRateLimit(req: Request, res: Response, next: Function) {
  const ip = req.ip || 'unknown';
  const routeKey = `${ip}:${req.path}`;
  // Five failed credentials must reach account-lockout. The sixth login call
  // is deliberately allowed through so it returns the durable ACCOUNT_LOCKED
  // response; subsequent attempts in the minute are throttled at the edge.
  const limit = req.path === '/login' ? 6 : 5;
  const now = Date.now();
  const entry = rateLimitStore.get(routeKey);
  if (!entry || now > entry.resetAt) {
    rateLimitStore.set(routeKey, { count: 1, resetAt: now + 60_000 });
    return next();
  }
  if (entry.count >= limit) {
    return res.status(429).json({ error: 'Too many attempts. Try again in a minute.' });
  }
  entry.count++;
  next();
}

/**
 * Test-only reset seam. Production callers cannot clear the limiter. Vitest
 * exposes VITEST=true even when a container inherited NODE_ENV from the
 * running backend. Keeping the reset beside the module-owned store prevents
 * tests from reaching into implementation details or weakening the live policy.
 */
export function resetAuthRateLimitForTests(): void {
  if (process.env.VITEST !== 'true' && process.env.NODE_ENV !== 'test') {
    throw new Error('resetAuthRateLimitForTests is only available in test mode');
  }
  rateLimitStore.clear();
}
// Cleanup stale entries every 5 minutes
const rateLimitCleanup = setInterval(() => {
  const now = Date.now();
  for (const [k, v] of rateLimitStore) {
    if (now > v.resetAt) rateLimitStore.delete(k);
  }
}, 5 * 60_000);
rateLimitCleanup.unref();

// --- Helpers ---

function getJwtSecret(): string {
  const secret = process.env.JWT_SECRET;
  if (!secret) throw new Error('JWT_SECRET is not configured');
  return secret;
}

function generateAccessToken(userId: string): string {
  return jwt.sign({ userId }, getJwtSecret(), { expiresIn: '15m', algorithm: 'HS256' });
}

function generateRefreshToken(): string {
  return crypto.randomBytes(48).toString('hex');
}

function hashToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex');
}

async function createSession(userId: string) {
  const refreshToken = generateRefreshToken();
  const refreshTokenHash = hashToken(refreshToken);
  const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000); // 30 days

  await prisma.session.create({
    data: { userId, refreshTokenHash, expiresAt },
  });

  const accessToken = generateAccessToken(userId);
  return { accessToken, refreshToken, expiresAt };
}

// --- Validation Schemas ---

const registerSchema = z.object({
  email: z.string().email('Invalid email address'),
  password: z.string().min(8, 'Password must be at least 8 characters'),
  name: z.string().min(1, 'Name is required'),
});

const loginSchema = z.object({
  email: z.string().email('Invalid email address'),
  password: z.string().min(1, 'Password is required'),
});

const refreshSchema = z.object({
  refreshToken: z.string().min(1, 'Refresh token is required'),
});

const updateProfileSchema = z.object({
  name: z.string().min(1).max(100).optional(),
  avatar: z.string().url().nullable().optional(),
});

const changePasswordSchema = z.object({
  currentPassword: z.string().min(1, 'Current password is required'),
  newPassword: z.string().min(8, 'New password must be at least 8 characters'),
});

const forgotPasswordSchema = z.object({
  email: z.string().email('Invalid email address'),
});

const resetPasswordSchema = z.object({
  token: z.string().min(1, 'Token is required'),
  password: z.string().min(8, 'Password must be at least 8 characters'),
});

const logoutSchema = z.object({
  refreshToken: z.string().min(1, 'Refresh token is required'),
});

// --- Routes ---

// POST /auth/register
router.post('/register', authRateLimit, async (req: Request, res: Response) => {
  const parsed = registerSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }

  const { email, password, name } = parsed.data;

  const existing = await prisma.user.findUnique({ where: { email } });
  if (existing) {
    throw new AppError(409, 'Email already registered', 'EMAIL_TAKEN');
  }

  const passwordHash = await bcrypt.hash(password, 12);
  const requireVerify = isEmailVerifyRequired();
  const verifyToken = requireVerify ? crypto.randomBytes(32).toString('hex') : null;
  const verifyTokenHash = verifyToken ? hashToken(verifyToken) : null;
  const verifyExpires = requireVerify ? new Date(Date.now() + 24 * 60 * 60 * 1000) : null;

  // Wrap user-create + session-create in a transaction so a failure in
  // session creation rolls back the user — no orphaned accounts. The
  // P2002 catch handles the race where two requests with the same email
  // both pass the findUnique pre-check above and only Postgres's unique
  // constraint catches the dup. Without the catch, the second request
  // would surface as a generic UNIQUE_VIOLATION (409) instead of the
  // EMAIL_TAKEN code that mobile clients match on.
  let user: Awaited<ReturnType<typeof prisma.user.create>>;
  let tokens: { accessToken: string; refreshToken: string; expiresAt: Date };
  try {
    const result = await prisma.$transaction(async (tx) => {
      const created = await tx.user.create({
        data: {
          email,
          passwordHash,
          name,
          emailVerified: !requireVerify,
          emailVerifyToken: verifyTokenHash,
          emailVerifyExpires: verifyExpires,
        },
      });
      const refreshToken = generateRefreshToken();
      const refreshTokenHash = hashToken(refreshToken);
      const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
      await tx.session.create({
        data: { userId: created.id, refreshTokenHash, expiresAt },
      });
      const accessToken = generateAccessToken(created.id);
      return { created, tokens: { accessToken, refreshToken, expiresAt } };
    });
    user = result.created;
    tokens = result.tokens;
  } catch (err: unknown) {
    if (typeof err === 'object' && err !== null && 'code' in err && (err as { code?: string }).code === 'P2002') {
      throw new AppError(409, 'Email already registered', 'EMAIL_TAKEN');
    }
    throw err;
  }

  if (requireVerify && verifyToken) {
    const baseUrl = getPublicBaseUrl();
    if (baseUrl) {
      const verifyUrl = `${baseUrl}/auth/verify-email?token=${encodeURIComponent(verifyToken)}`;
      sendVerificationEmail(email, verifyUrl).catch(() => {
        // best-effort — don't fail registration if Resend is down
      });
    } else {
      logger.warn(
        '[register] PUBLIC_BASE_URL not set — skipping verification email to avoid an unsafe Host-derived link. Set PUBLIC_BASE_URL.',
      );
    }
  }

  res.status(201).json({
    user: {
      id: user.id,
      email: user.email,
      name: user.name,
      avatar: user.avatar,
      emailVerified: user.emailVerified,
    },
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    requireEmailVerify: requireVerify,
  });
});

// POST /auth/guest — per-device guest session for an app without accounts
// (APP_AUTH=none; ADR-0046). The app generates a random device id once, keeps
// it locally and sends it here; the backend upserts a guest User for it
// (email `guest-<deviceId>@guest.invalid`, an unguessable password hash so
// /login can never match it, emailVerified so nothing ever mails it) and
// answers exactly like /login — so every generated feature route, which
// always requires a bearer token, works scoped to that installation, and the
// normal /refresh flow keeps the session alive. Answers 404 in every other
// mode: accounts exist there. Rate-limited like the other auth routes.
const guestSchema = z.object({
  deviceId: z.string().regex(/^[A-Za-z0-9_-]{16,64}$/, 'Invalid device id'),
});

router.post('/guest', authRateLimit, async (req: Request, res: Response) => {
  if ((process.env.APP_AUTH || 'required') !== 'none') {
    throw new AppError(404, 'Guest sessions are only available in apps without accounts', 'GUEST_DISABLED');
  }
  const parsed = guestSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }
  const email = `guest-${parsed.data.deviceId}@guest.invalid`;

  let user = await prisma.user.findUnique({ where: { email } });
  if (!user) {
    // A real bcrypt hash of a random secret: bcrypt.compare in /login always
    // returns false for it and never throws on a malformed hash.
    const passwordHash = await bcrypt.hash(crypto.randomBytes(32).toString('hex'), 10);
    try {
      user = await prisma.user.create({ data: { email, passwordHash, name: 'Guest', emailVerified: true } });
    } catch (err: unknown) {
      // Two first launches of the same install racing: keep the winner.
      if (typeof err === 'object' && err !== null && 'code' in err && (err as { code?: string }).code === 'P2002') {
        user = await prisma.user.findUniqueOrThrow({ where: { email } });
      } else {
        throw err;
      }
    }
  }

  const tokens = await createSession(user.id);
  res.json({
    user: {
      id: user.id,
      email: user.email,
      name: user.name,
      avatar: user.avatar,
      emailVerified: user.emailVerified,
    },
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
  });
});

// GET /auth/verify-email?token=...  — flips emailVerified=true. Endpoint
// is always present so existing apps don't 404; if REQUIRE_EMAIL_VERIFY
// is off the user is already verified and the token won't match anything.
router.get('/verify-email', async (req: Request, res: Response) => {
  const token = typeof req.query.token === 'string' ? req.query.token : '';
  if (!token) throw new AppError(400, 'Missing token');
  const tokenHash = hashToken(token);
  const user = await prisma.user.findFirst({
    where: { emailVerifyToken: tokenHash, emailVerifyExpires: { gt: new Date() } },
  });
  if (!user) throw new AppError(400, 'Invalid or expired verification token', 'EMAIL_VERIFY_INVALID');
  await prisma.user.update({
    where: { id: user.id },
    data: { emailVerified: true, emailVerifyToken: null, emailVerifyExpires: null },
  });
  res.json({ message: 'Email verified' });
});

const resendVerifySchema = z.object({ email: z.string().email() });

// POST /auth/resend-verification — re-issue a verification mail. No-op
// if email is already verified or if EMAIL_ENABLED is off (sendEmail
// returns skipped:true). Throttled to 1/min by the per-IP authRateLimit.
router.post('/resend-verification', authRateLimit, async (req: Request, res: Response) => {
  if (!isEmailVerifyRequired()) {
    return res.json({ message: 'Email verification is not required for this app' });
  }
  const parsed = resendVerifySchema.safeParse(req.body);
  if (!parsed.success) throw new AppError(400, 'Invalid email');
  const user = await prisma.user.findUnique({ where: { email: parsed.data.email } });
  // Always 200 to avoid email enumeration
  if (!user || user.emailVerified) {
    return res.json({ message: 'If that email exists and is unverified, a new link has been sent' });
  }
  const verifyToken = crypto.randomBytes(32).toString('hex');
  await prisma.user.update({
    where: { id: user.id },
    data: {
      emailVerifyToken: hashToken(verifyToken),
      emailVerifyExpires: new Date(Date.now() + 24 * 60 * 60 * 1000),
    },
  });
  const baseUrl = getPublicBaseUrl();
  if (baseUrl) {
    const verifyUrl = `${baseUrl}/auth/verify-email?token=${encodeURIComponent(verifyToken)}`;
    await sendVerificationEmail(parsed.data.email, verifyUrl).catch(() => {});
  } else {
    logger.warn(
      '[resend-verification] PUBLIC_BASE_URL not set — skipping verification email to avoid an unsafe Host-derived link. Set PUBLIC_BASE_URL.',
    );
  }
  res.json({ message: 'If that email exists and is unverified, a new link has been sent' });
});

// POST /auth/login
router.post('/login', authRateLimit, async (req: Request, res: Response) => {
  const parsed = loginSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }

  const { email, password } = parsed.data;

  const lockoutTtl = await getLockoutTtl(email);
  if (lockoutTtl !== null) {
    res.set('Retry-After', String(lockoutTtl));
    throw new AppError(
      429,
      `Too many failed attempts. Try again in ${Math.ceil(lockoutTtl / 60)} minute(s).`,
      'ACCOUNT_LOCKED',
    );
  }

  const user = await prisma.user.findUnique({ where: { email } });
  if (!user) {
    await recordFailedLogin(email);
    throw new AppError(401, 'Invalid email or password');
  }

  const valid = await bcrypt.compare(password, user.passwordHash);
  if (!valid) {
    await recordFailedLogin(email);
    throw new AppError(401, 'Invalid email or password');
  }

  if (isEmailVerifyRequired() && !user.emailVerified) {
    throw new AppError(403, 'Email not verified. Check your inbox for the verification link.', 'EMAIL_NOT_VERIFIED');
  }

  await clearFailedAttempts(email);
  const tokens = await createSession(user.id);

  res.json({
    user: {
      id: user.id,
      email: user.email,
      name: user.name,
      avatar: user.avatar,
      emailVerified: user.emailVerified,
    },
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
  });
});

// POST /auth/refresh
router.post('/refresh', async (req: Request, res: Response) => {
  const parsed = refreshSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }

  const { refreshToken } = parsed.data;
  const refreshTokenHash = hashToken(refreshToken);

  const session = await prisma.session.findUnique({ where: { refreshTokenHash } });
  if (!session) {
    throw new AppError(401, 'Invalid refresh token');
  }

  if (session.expiresAt < new Date()) {
    await prisma.session.delete({ where: { id: session.id } });
    throw new AppError(401, 'Refresh token has expired');
  }

  // Delete old session (token rotation)
  await prisma.session.delete({ where: { id: session.id } });

  // Create new session with fresh tokens
  const tokens = await createSession(session.userId);

  res.json({
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
  });
});

// GET /auth/me
router.get('/me', authMiddleware, async (req: Request, res: Response) => {
  res.json({ user: req.user });
});

// PUT /auth/me — update profile (name, avatar)
router.put('/me', authMiddleware, async (req: Request, res: Response) => {
  const parsed = updateProfileSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }

  const { name, avatar } = parsed.data;
  // TMPL-3 (B6): allow null so clearing the avatar stores NULL, not ''. The
  // profile schema is `z.string().url().nullable()`, and an empty string is
  // neither a URL nor null — it round-tripped back to the client as a broken
  // image src. `?? null` preserves an explicit clear; the record type is
  // widened to string | null to carry it.
  const data: Record<string, string | null> = {};
  if (name) data.name = name;
  if (avatar !== undefined) data.avatar = avatar ?? null;

  if (Object.keys(data).length === 0) throw new AppError(400, 'Nothing to update');

  const user = await prisma.user.update({
    where: { id: req.user!.id },
    select: { id: true, email: true, name: true, avatar: true, createdAt: true, updatedAt: true },
    data,
  });
  res.json({ user });
});

// POST /auth/change-password — change password (requires current password)
router.post('/change-password', authMiddleware, async (req: Request, res: Response) => {
  const parsed = changePasswordSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }

  const { currentPassword, newPassword } = parsed.data;

  const user = await prisma.user.findUnique({ where: { id: req.user!.id } });
  if (!user) throw new AppError(404, 'User not found');

  const valid = await bcrypt.compare(currentPassword, user.passwordHash);
  if (!valid) throw new AppError(401, 'Current password is incorrect');

  const passwordHash = await bcrypt.hash(newPassword, 12);
  await prisma.user.update({ where: { id: req.user!.id }, data: { passwordHash } });

  res.json({ message: 'Password changed successfully' });
});

// POST /auth/forgot-password
router.post('/forgot-password', authRateLimit, async (req: Request, res: Response) => {
  const parsed = forgotPasswordSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }

  const { email } = parsed.data;
  const user = await prisma.user.findUnique({ where: { email } });

  // Always return success to avoid email enumeration
  if (user) {
    const resetToken = crypto.randomBytes(32).toString('hex');
    const resetTokenHash = hashToken(resetToken);
    const expiresAt = new Date(Date.now() + 60 * 60 * 1000); // 1 hour

    // Store reset token on user (upsert-safe)
    await prisma.user.update({
      where: { id: user.id },
      data: { passwordResetToken: resetTokenHash, passwordResetExpires: expiresAt },
    });

    // Build the reset link from server-side config ONLY — never from the
    // request Host header (a forged Host would deliver the victim's reset
    // token to an attacker domain → account takeover). Token is URL-encoded.
    const baseUrl = getPublicBaseUrl();
    if (baseUrl) {
      const resetUrl = `${baseUrl}/auth/reset-password?token=${encodeURIComponent(resetToken)}`;
      // Best-effort send — never fail the request (and never reveal whether
      // the address exists) if the mail provider is down. The raw token is
      // never logged; it only leaves the system inside this email.
      sendPasswordResetEmail(email, resetUrl).catch(() => {});
    } else {
      logger.warn(
        '[forgot-password] PUBLIC_BASE_URL not set — skipping reset email to avoid an unsafe Host-derived link. Set PUBLIC_BASE_URL.',
      );
    }
  }

  res.json({ message: 'If that email exists, a reset link has been sent' });
});

// POST /auth/reset-password
router.post('/reset-password', authRateLimit, async (req: Request, res: Response) => {
  const parsed = resetPasswordSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }

  const { token, password } = parsed.data;
  const tokenHash = hashToken(token);

  const user = await prisma.user.findFirst({
    where: {
      passwordResetToken: tokenHash,
      passwordResetExpires: { gt: new Date() },
    },
  });

  if (!user) {
    throw new AppError(400, 'Invalid or expired reset token');
  }

  const passwordHash = await bcrypt.hash(password, 12);
  await prisma.user.update({
    where: { id: user.id },
    data: {
      passwordHash,
      passwordResetToken: null,
      passwordResetExpires: null,
    },
  });

  // Invalidate all sessions
  await prisma.session.deleteMany({ where: { userId: user.id } });

  res.json({ message: 'Password has been reset' });
});

// POST /auth/logout
router.post('/logout', authRateLimit, async (req: Request, res: Response) => {
  const parsed = logoutSchema.safeParse(req.body);
  if (!parsed.success) {
    throw new AppError(400, parsed.error.errors.map((e) => e.message).join(', '));
  }

  const { refreshToken } = parsed.data;
  const refreshTokenHash = hashToken(refreshToken);

  // Delete session if it exists (ignore if not found)
  await prisma.session.deleteMany({ where: { refreshTokenHash } });

  res.json({ message: 'Logged out' });
});

export default router;
