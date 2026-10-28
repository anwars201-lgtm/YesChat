import { describe, expect, it } from 'vitest';
import request from 'supertest';

import app from '../src/app';
import { prisma } from '../src/lib/prisma';

const apiKeyHeader = (): { 'X-Api-Key': string } => ({
  'X-Api-Key': process.env.PROJECT_API_KEY || 'test-api-key',
});

async function register(email: string) {
  return request(app)
    .post('/auth/register')
    .set(apiKeyHeader())
    .send({ email, password: 'Sup3rsecret!', name: 'Tester' });
}

describe('auth routes', () => {
  it('register → returns user + tokens, persists row', async () => {
    const res = await register('alice@example.com');
    expect(res.status).toBe(201);
    expect(res.body.user.email).toBe('alice@example.com');
    expect(typeof res.body.accessToken).toBe('string');
    expect(typeof res.body.refreshToken).toBe('string');

    const persisted = await prisma.user.findUnique({ where: { email: 'alice@example.com' } });
    expect(persisted).not.toBeNull();
    // Default REQUIRE_EMAIL_VERIFY=false → user is verified out of the gate
    expect(persisted?.emailVerified).toBe(true);
  });

  it('login → returns 200 with valid credentials', async () => {
    await register('bob@example.com');
    const res = await request(app)
      .post('/auth/login')
      .set(apiKeyHeader())
      .send({ email: 'bob@example.com', password: 'Sup3rsecret!' });
    expect(res.status).toBe(200);
    expect(res.body.accessToken).toBeDefined();
  });

  it('login → 401 with wrong password', async () => {
    await register('carol@example.com');
    const res = await request(app)
      .post('/auth/login')
      .set(apiKeyHeader())
      .send({ email: 'carol@example.com', password: 'WrongPassword!' });
    expect(res.status).toBe(401);
  });

  it('auth limiter → isolates register and login budgets', async () => {
    for (let i = 0; i < 5; i++) {
      const registerAttempt = await register(`budget-${i}@example.com`);
      expect(registerAttempt.status).toBe(201);
    }
    const loginAttempt = await request(app)
      .post('/auth/login')
      .set(apiKeyHeader())
      .send({ email: 'missing@example.com', password: 'WrongPassword!' });
    expect(loginAttempt.status).toBe(401);
  });

  it('auth limiter → throttles the seventh login request within one minute', async () => {
    await register('edge-limit@example.com');
    for (let i = 0; i < 6; i++) {
      await request(app)
        .post('/auth/login')
        .set(apiKeyHeader())
        .send({ email: 'edge-limit@example.com', password: 'WrongPassword!' });
    }
    const throttled = await request(app)
      .post('/auth/login')
      .set(apiKeyHeader())
      .send({ email: 'edge-limit@example.com', password: 'WrongPassword!' });
    expect(throttled.status).toBe(429);
    expect(throttled.body.error).toContain('Too many attempts');
  });

  it('refresh → rotates the refresh token', async () => {
    const reg = await register('dan@example.com');
    const first = reg.body.refreshToken;
    const res = await request(app).post('/auth/refresh').set(apiKeyHeader()).send({ refreshToken: first });
    expect(res.status).toBe(200);
    expect(res.body.refreshToken).toBeDefined();
    expect(res.body.refreshToken).not.toBe(first);

    // Old refresh token must no longer work
    const reuse = await request(app).post('/auth/refresh').set(apiKeyHeader()).send({ refreshToken: first });
    expect(reuse.status).toBe(401);
  });

  it('login → 5x wrong password locks the account (when Redis is available)', async () => {
    if (!process.env.REDIS_URL) {
      // Lockout requires Redis; skip when not configured (test infra runs
      // both with and without Redis). The lockout module fail-opens.
      return;
    }
    await register('eve@example.com');
    for (let i = 0; i < 5; i++) {
      await request(app)
        .post('/auth/login')
        .set(apiKeyHeader())
        .send({ email: 'eve@example.com', password: 'WrongPassword!' });
    }
    const res = await request(app)
      .post('/auth/login')
      .set(apiKeyHeader())
      .send({ email: 'eve@example.com', password: 'Sup3rsecret!' });
    expect(res.status).toBe(429);
    expect(res.body.code).toBe('ACCOUNT_LOCKED');
  });
});
