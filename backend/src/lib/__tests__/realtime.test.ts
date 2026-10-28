import { EventEmitter } from 'node:events';

import { describe, it, expect, vi } from 'vitest';
import type { Request, Response, NextFunction } from 'express';
import type Redis from 'ioredis';

import * as redisModule from '../redis';
import { openSSEStream, redisChannelName, sseChannelOwnershipGuard } from '../realtime';

// Pure-logic unit test (no DB, no Redis) for the SSE ownership guard that runs
// when SSE auth is enabled (now the default — SSE_REQUIRE_AUTH!=='false'). The
// guard runs AFTER authMiddleware, so `req.user` is populated for an authed
// request; it only adds ownership enforcement on private `user:<id>` channels.

// The captured status/body live in closures, not as extra properties on the
// Response object — assigning them onto `res` would force `res.status`/`res.json`
// to an intersection return type that Express's own overloads don't satisfy.
function mockRes(): { res: Response; getStatus: () => number; getBody: () => unknown } {
  let statusCode = 0;
  let body: unknown;
  const res = {
    status(code: number) {
      statusCode = code;
      return res;
    },
    json(payload: unknown) {
      body = payload;
      return res;
    },
  } as unknown as Response;
  return { res, getStatus: () => statusCode, getBody: () => body };
}

function run(channel: string, user?: { id: string | number }): { status: number; body: unknown; nextCalled: boolean } {
  const req = { params: { channel }, user } as unknown as Request;
  const { res, getStatus, getBody } = mockRes();
  let nextCalled = false;
  const next: NextFunction = () => {
    nextCalled = true;
  };
  sseChannelOwnershipGuard(req, res, next);
  return { status: getStatus(), body: getBody(), nextCalled };
}

describe('sseChannelOwnershipGuard', () => {
  it('allows a user to subscribe to their OWN user:<id> channel', () => {
    const { status, nextCalled } = run('user:42', { id: '42' });
    expect(nextCalled).toBe(true);
    expect(status).toBe(0); // no error status set
  });

  it('blocks subscribing to ANOTHER user:<id> channel (403)', () => {
    const { status, body, nextCalled } = run('user:99', { id: '42' });
    expect(nextCalled).toBe(false);
    expect(status).toBe(403);
    expect(body).toEqual({ error: 'Forbidden' });
  });

  it('blocks an unauthenticated request on a user:<id> channel (403)', () => {
    const { status, nextCalled } = run('user:42', undefined);
    expect(nextCalled).toBe(false);
    expect(status).toBe(403);
  });

  it('allows any authenticated user on a shared conversation:<id> channel', () => {
    const { status, nextCalled } = run('conversation:7', { id: '42' });
    expect(nextCalled).toBe(true);
    expect(status).toBe(0);
  });

  it('compares the user id as a string (numeric id 42 matches channel "user:42")', () => {
    const { nextCalled } = run('user:42', { id: 42 });
    expect(nextCalled).toBe(true);
  });

  it('keeps nested control channels private to the user in the first segment', () => {
    expect(run('user:42:access:trips:trip-1', { id: '42' }).nextCalled).toBe(true);
    expect(run('user:42:access:trips:trip-1', { id: '99' })).toMatchObject({
      status: 403,
      nextCalled: false,
    });
  });
});

class FakeSubscriber extends EventEmitter {
  readonly subscribe = vi.fn(async (..._channels: string[]) => undefined);
  readonly unsubscribe = vi.fn(async (..._channels: string[]) => undefined);
}

function mockStream(): {
  req: Request;
  res: Response;
  writes: string[];
  ended: () => number;
} {
  const request = new EventEmitter();
  const writes: string[] = [];
  let endCount = 0;
  const response = {
    setHeader: vi.fn(),
    flushHeaders: vi.fn(),
    write: vi.fn((chunk: string) => {
      writes.push(chunk);
      return true;
    }),
    end: vi.fn(() => {
      endCount += 1;
    }),
  };
  return {
    req: request as unknown as Request,
    res: response as unknown as Response,
    writes,
    ended: () => endCount,
  };
}

describe('openSSEStream access revocation', () => {
  it('closes only the targeted caller and keeps the shared resource subscription alive', async () => {
    const previousPrefix = process.env.REDIS_PREFIX;
    delete process.env.REDIS_PREFIX;
    const subscriber = new FakeSubscriber();
    const subscriberSpy = vi.spyOn(redisModule, 'getRedisSubscriber').mockReturnValue(subscriber as unknown as Redis);
    const first = mockStream();
    const second = mockStream();

    try {
      openSSEStream(first.req, first.res, 'trips:trip-1', {
        closeChannel: 'user:user-1:access:trips:trip-1',
      });
      openSSEStream(second.req, second.res, 'trips:trip-1', {
        closeChannel: 'user:user-2:access:trips:trip-1',
      });

      expect(subscriber.subscribe).toHaveBeenCalledWith('trips:trip-1');
      expect(subscriber.subscribe).toHaveBeenCalledWith('user:user-1:access:trips:trip-1');
      expect(subscriber.subscribe).toHaveBeenCalledWith('user:user-2:access:trips:trip-1');
      expect(subscriber.subscribe.mock.calls.filter(([channel]) => channel === 'trips:trip-1')).toHaveLength(1);

      subscriber.emit('message', 'trips:trip-1', '{"type":"trip_updated"}');
      await vi.waitFor(() => {
        expect(first.writes).toContain('data: {"type":"trip_updated"}\n\n');
        expect(second.writes).toContain('data: {"type":"trip_updated"}\n\n');
      });

      subscriber.emit('message', 'user:user-1:access:trips:trip-1', '{"type":"access_revoked"}');
      expect(first.ended()).toBe(1);
      expect(second.ended()).toBe(0);
      expect(first.writes.join('')).not.toContain('access_revoked');
      expect(subscriber.unsubscribe).toHaveBeenCalledWith('user:user-1:access:trips:trip-1');
      expect(subscriber.unsubscribe).not.toHaveBeenCalledWith('trips:trip-1');

      subscriber.emit('message', 'trips:trip-1', '{"type":"trip_updated_again"}');
      await vi.waitFor(() => {
        expect(first.writes.join('')).not.toContain('trip_updated_again');
        expect(second.writes).toContain('data: {"type":"trip_updated_again"}\n\n');
      });

      (second.req as unknown as EventEmitter).emit('close');
      expect(second.ended()).toBe(1);
      expect(subscriber.unsubscribe).toHaveBeenCalledWith('user:user-2:access:trips:trip-1');
      expect(subscriber.unsubscribe).toHaveBeenCalledWith('trips:trip-1');
    } finally {
      if (first.ended() === 0) (first.req as unknown as EventEmitter).emit('close');
      if (second.ended() === 0) (second.req as unknown as EventEmitter).emit('close');
      subscriberSpy.mockRestore();
      if (previousPrefix === undefined) delete process.env.REDIS_PREFIX;
      else process.env.REDIS_PREFIX = previousPrefix;
    }
  });

  it('fails closed before forwarding a resource event when access was revoked', async () => {
    const previousPrefix = process.env.REDIS_PREFIX;
    delete process.env.REDIS_PREFIX;
    const subscriber = new FakeSubscriber();
    const subscriberSpy = vi.spyOn(redisModule, 'getRedisSubscriber').mockReturnValue(subscriber as unknown as Redis);
    const stream = mockStream();
    const authorize = vi.fn(async () => false);

    try {
      openSSEStream(stream.req, stream.res, 'trips:trip-2', { authorize });
      subscriber.emit('message', 'trips:trip-2', '{"type":"private_update"}');

      await vi.waitFor(() => expect(stream.ended()).toBe(1));
      expect(authorize).toHaveBeenCalledTimes(1);
      expect(stream.writes.join('')).not.toContain('private_update');
      expect(subscriber.unsubscribe).toHaveBeenCalledWith('trips:trip-2');
    } finally {
      if (stream.ended() === 0) (stream.req as unknown as EventEmitter).emit('close');
      subscriberSpy.mockRestore();
      if (previousPrefix === undefined) delete process.env.REDIS_PREFIX;
      else process.env.REDIS_PREFIX = previousPrefix;
    }
  });
});

describe('redisChannelName', () => {
  it('qualifies pub/sub channels with the tenant or isolated test-run prefix', () => {
    const previous = process.env.REDIS_PREFIX;
    process.env.REDIS_PREFIX = 'proj_example:test:run_1';
    try {
      expect(redisChannelName('conversation:7')).toBe('proj_example:test:run_1:conversation:7');
    } finally {
      if (previous === undefined) delete process.env.REDIS_PREFIX;
      else process.env.REDIS_PREFIX = previous;
    }
  });

  it('keeps local Redis setups without a configured prefix compatible', () => {
    const previous = process.env.REDIS_PREFIX;
    delete process.env.REDIS_PREFIX;
    try {
      expect(redisChannelName('conversation:7')).toBe('conversation:7');
    } finally {
      if (previous !== undefined) process.env.REDIS_PREFIX = previous;
    }
  });
});
