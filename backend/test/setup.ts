// Per-file setup. Runs AFTER global-setup.ts has injected the unique
// schema into DATABASE_URL, so prisma.ts loads against the right schema.
// Truncates auth tables between tests so each starts clean.

import { afterAll, beforeEach, afterEach } from 'vitest';

import { prisma } from '../src/lib/prisma';
import { resetAuthRateLimitForTests } from '../src/routes/auth.routes';

beforeEach(() => {
  // Every test gets a fresh request budget. The production limiter remains
  // module-global and unchanged; only Vitest/test mode can call this seam.
  resetAuthRateLimitForTests();
});

afterEach(async () => {
  // Order matters: child rows first, then parents.
  await prisma.session.deleteMany({});
  await prisma.user.deleteMany({});
});

afterAll(async () => {
  await prisma.$disconnect();
});
