import bcrypt from 'bcryptjs';

import { prisma } from './prisma';

/**
 * Seeds a Prisma model if the table is empty.
 * Usage: await seedIfEmpty('user', [{ email: '...', ... }])
 */
export async function seedIfEmpty<T extends Record<string, unknown>>(
  model: string,
  data: T[],
): Promise<{ seeded: boolean; count: number }> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const delegate = (prisma as Record<string, any>)[model];

  if (!delegate) {
    throw new Error(`Unknown Prisma model: ${model}`);
  }

  const count = await delegate.count();

  if (count > 0) {
    return { seeded: false, count };
  }

  await delegate.createMany({ data });

  return { seeded: true, count: data.length };
}

/**
 * Demo-user credentials + profile.
 *
 * Login screen can show a "Try with demo account" button that auto-fills
 * these — much better first-launch UX than "register before you can see
 * anything". Guarded against `NODE_ENV === 'production'` by default.
 */
export const DEMO_USER = {
  email: 'demo@example.com',
  password: 'Demo123!',
  name: 'Demo User',
  avatar: 'https://api.dicebear.com/9.x/avataaars/svg?seed=demo&backgroundColor=6366F1',
};

/**
 * Upsert the demo user so `npm run db:seed:demo` is idempotent. Returns the
 * user id so callers can link demo data to it (posts, items, etc.) without
 * re-looking it up.
 *
 * Skipped entirely when `NODE_ENV === 'production'` unless
 * `ALLOW_DEMO_USER_IN_PROD=1`. Keeps the door closed in prod while leaving
 * an escape hatch for staging.
 */
export async function upsertDemoUser(): Promise<string | null> {
  if (process.env.NODE_ENV === 'production' && process.env.ALLOW_DEMO_USER_IN_PROD !== '1') {
    return null;
  }
  const passwordHash = await bcrypt.hash(DEMO_USER.password, 10);
  const user = await prisma.user.upsert({
    where: { email: DEMO_USER.email },
    update: { name: DEMO_USER.name, avatar: DEMO_USER.avatar },
    create: {
      email: DEMO_USER.email,
      passwordHash,
      name: DEMO_USER.name,
      avatar: DEMO_USER.avatar,
    },
  });
  return user.id;
}

// Run seed if this file is executed directly
if (require.main === module) {
  async function runSeed() {
    // eslint-disable-next-line no-console
    console.log('Running seed script...');
    // AI: Add your seed data here using seedIfEmpty()
    // eslint-disable-next-line no-console
    console.log('Seed script completed.');
  }
  runSeed().catch((e) => {
    // eslint-disable-next-line no-console
    console.error(e);
    process.exit(1);
  });
}
