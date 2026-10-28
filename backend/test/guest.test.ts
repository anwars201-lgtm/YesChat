// Guest sessions for apps without accounts (APP_AUTH=none; ADR-0046).
//
// Every generated feature route requires a bearer token. Before 2026-09-14 a
// no-auth app had none (and `/auth` was not even mounted, which 404'd the
// template + generated backend tests at foundation verify). `POST /auth/guest`
// opens a per-device session that behaves exactly like a login.
//
// The first block runs only when the container's APP_AUTH is `none`; the
// second only otherwise — so the same file proves both the guest contract and
// that accounts-mode apps never expose it.
import { describe, expect, it } from 'vitest';
import request from 'supertest';

import app from '../src/app';
import { prisma } from '../src/lib/prisma';

const apiKeyHeader = (): { 'X-Api-Key': string } => ({
  'X-Api-Key': process.env.PROJECT_API_KEY || 'test-api-key',
});

const DEVICE_ID = 'device0123456789abcdef';

function guest(deviceId: string = DEVICE_ID) {
  return request(app).post('/auth/guest').set(apiKeyHeader()).send({ deviceId });
}

const authMode = process.env.APP_AUTH || 'required';

describe.skipIf(authMode !== 'none')('guest sessions (APP_AUTH=none)', () => {
  it('opens a session for a device id and answers like /login', async () => {
    const res = await guest();
    expect(res.status).toBe(200);
    expect(res.body.user.email).toBe(`guest-${DEVICE_ID}@guest.invalid`);
    expect(res.body.user.name).toBe('Guest');
    expect(res.body.user.emailVerified).toBe(true);
    expect(typeof res.body.accessToken).toBe('string');
    expect(typeof res.body.refreshToken).toBe('string');

    const persisted = await prisma.user.findUnique({ where: { email: res.body.user.email } });
    expect(persisted?.emailVerified).toBe(true);
  });

  it('is idempotent per device id: the same guest user, a new session each time', async () => {
    const first = await guest();
    const second = await guest();
    expect(second.status).toBe(200);
    expect(second.body.user.id).toBe(first.body.user.id);
    // Access tokens minted within the same second are identical JWTs; the
    // refresh token is random per session and proves a second session.
    expect(second.body.refreshToken).not.toBe(first.body.refreshToken);
    expect(await prisma.session.count({ where: { userId: first.body.user.id } })).toBe(2);
    expect(await prisma.user.count()).toBe(1);
  });

  it('different devices get different guests', async () => {
    const a = await guest('device-aaaaaaaaaaaaaaaa');
    const b = await guest('device-bbbbbbbbbbbbbbbb');
    expect(a.body.user.id).not.toBe(b.body.user.id);
  });

  it('rejects a malformed device id', async () => {
    expect((await guest('short')).status).toBe(400);
    expect((await guest('has spaces and symbols!!')).status).toBe(400);
    expect((await request(app).post('/auth/guest').set(apiKeyHeader()).send({})).status).toBe(400);
  });

  it('the guest bearer works on protected routes and the session refreshes', async () => {
    const opened = await guest();
    const me = await request(app).get('/auth/me').set(apiKeyHeader()).set('Authorization', `Bearer ${opened.body.accessToken}`);
    expect(me.status).toBe(200);
    expect(me.body.user.id).toBe(opened.body.user.id);

    const refreshed = await request(app)
      .post('/auth/refresh')
      .set(apiKeyHeader())
      .send({ refreshToken: opened.body.refreshToken });
    expect(refreshed.status).toBe(200);
    expect(typeof refreshed.body.accessToken).toBe('string');
  });

  it('a guest can never log in with a password', async () => {
    const opened = await guest();
    const login = await request(app)
      .post('/auth/login')
      .set(apiKeyHeader())
      .send({ email: opened.body.user.email, password: 'Sup3rsecret!' });
    expect(login.status).toBe(401);
  });
});

describe.skipIf(authMode === 'none')('guest sessions are disabled when the app has accounts', () => {
  it('answers 404 GUEST_DISABLED', async () => {
    const res = await guest();
    expect(res.status).toBe(404);
    expect(res.body.code).toBe('GUEST_DISABLED');
    expect(await prisma.user.count()).toBe(0);
  });
});
