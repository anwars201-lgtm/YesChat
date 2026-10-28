import type { Express, Request, Response, NextFunction, RequestHandler } from 'express';

import { logger } from './logger';
import { getRedis, getRedisSubscriber } from './redis';

/** ioredis keyPrefix deliberately does not transform Pub/Sub channel names.
 * Apply the same tenant/run namespace explicitly so customer ACL channel
 * scopes work and concurrent test suites cannot hear each other's events. */
export function redisChannelName(channel: string): string {
  const prefix = process.env.REDIS_PREFIX;
  return prefix ? `${prefix}:${channel}` : channel;
}

/**
 * TMPL-6a: ownership guard for authenticated SSE. Wired in by default (opt out
 * with SSE_REQUIRE_AUTH=false, see app.ts), AFTER authMiddleware has populated
 * `req.user`. A `user:<id>` channel (including nested channels such as
 * `user:<id>:access:<resource>`) is private to that user; every other
 * namespaced channel — `conversation:<id>` (the form the chat app actually
 * uses) or any app-defined channel — is allowed for any authenticated user,
 * because a generic template cannot know conversation membership, so the
 * verified JWT is the gate. Apps that need per-conversation membership checks
 * can tighten this. Clients must open the stream with `Authorization: Bearer
 * <token>` (+ the usual `X-API-Key`); the generated SSE clients do.
 */
export function sseChannelOwnershipGuard(req: Request, res: Response, next: NextFunction): void {
  const channel = req.params.channel ?? '';
  const userId = req.user?.id;
  if (channel.startsWith('user:')) {
    const target = channel.slice('user:'.length).split(':', 1)[0];
    if (!userId || target !== String(userId)) {
      res.status(403).json({ error: 'Forbidden' });
      return;
    }
  }
  next();
}

/**
 * Server-Sent Events (SSE) helper for realtime features (chat, live
 * feeds, notifications). Backed by Redis pub/sub so the backend can
 * scale horizontally — any replica that receives a publish fans it out
 * to every connected subscriber on every replica.
 *
 * Split-connection model: Redis requires a connection in subscribe
 * mode to stop sending regular commands. We use the shared `getRedis()`
 * connection for publishes and a dedicated `getRedisSubscriber()`
 * connection for subscribes. Sharing would deadlock the cache/
 * rate-limit features.
 *
 * ## Wire it up
 *
 * ```ts
 * // src/app.ts
 * import { registerSSERoute } from './lib/realtime';
 * registerSSERoute(app);
 *
 * // src/routes/messages.routes.ts
 * import { sseBroadcast } from '../lib/realtime';
 *
 * router.post('/', authMiddleware, async (req, res) => {
 *   const message = await prisma.message.create({ data: { ... } });
 *   await sseBroadcast(`conversation:${message.conversationId}`, {
 *     type: 'message.created',
 *     message,
 *   });
 *   res.status(201).json(message);
 * });
 * ```
 *
 * ## Client side (Flutter)
 *
 * ```dart
 * final eventSource = EventSource.connect(
 *   '$apiUrl/events/conversation:${conversationId}',
 *   headers: {'Authorization': 'Bearer $token'},
 * );
 * eventSource.onMessage.listen((event) {
 *   final payload = jsonDecode(event.data);
 *   // ...
 * });
 * ```
 */

export async function sseBroadcast(channel: string, payload: unknown): Promise<void> {
  const pub = getRedis();
  if (!pub) {
    logger.warn({ channel }, '[realtime] publish skipped — Redis unavailable');
    return;
  }
  try {
    await pub.publish(redisChannelName(channel), JSON.stringify(payload));
  } catch (err) {
    logger.warn({ err, channel }, '[realtime] publish failed');
  }
}

/**
 * Mount the SSE event stream at `GET /events/:channel`. The channel
 * parameter is namespaced by the caller — typically `conversation:{id}`
 * or `user:{id}` — and must be URL-encoded if it contains special
 * characters. Authentication is delegated to the caller (wrap
 * `registerSSERoute` in your own authMiddleware if the stream is
 * private).
 */
/**
 * Ref-count of active SSE subscribers per channel. Because
 * `getRedisSubscriber()` returns a single shared ioredis connection,
 * calling `sub.unsubscribe(channel)` on the first client disconnect
 * would tear down the Redis-level subscription for EVERY other client
 * still listening on that channel. Track the count and only send
 * UNSUBSCRIBE when it drops to zero.
 */
const channelRefCount = new Map<string, number>();

export function registerSSERoute(app: Express, path = '/events/:channel', preHandlers: RequestHandler[] = []): void {
  app.get(path, ...preHandlers, (req: Request, res: Response) => {
    openSSEStream(req, res, req.params.channel);
  });
}

export interface SSEStreamOptions {
  /**
   * Optional caller-private channel that terminates this response without
   * forwarding the control payload to the client. Resource routers use this
   * when an authorization change (for example leaving a shared resource)
   * must revoke one caller's already-open stream immediately.
   */
  closeChannel?: string;
  /**
   * Optional fail-closed authorization check run immediately before each
   * resource event is delivered. This protects an already-open stream even if
   * the close-channel publish is delayed or unavailable.
   */
  authorize?: () => Promise<boolean>;
}

/** Open an SSE stream for an already-authorized resource channel. Generated
 * feature routers call this only AFTER their owner/member access predicate has
 * passed, which avoids exposing collaborative resource streams through the
 * generic authenticated `/events/:channel` route. */
export function openSSEStream(req: Request, res: Response, rawChannel: string, options: SSEStreamOptions = {}): void {
  const redisSubscriber = getRedisSubscriber();
  if (!redisSubscriber) {
    res.status(503).json({ error: 'Realtime unavailable — Redis not configured' });
    return;
  }
  // Preserve the non-null narrowing inside cleanup/event-handler closures.
  const sub = redisSubscriber;

  // SSE headers
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache, no-transform');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no'); // disables proxy buffering (nginx)
  res.flushHeaders();

  // Initial retry hint for EventSource clients (reconnect after 3s on drop)
  res.write('retry: 3000\n\n');

  const channel = redisChannelName(rawChannel);
  const closeChannel = options.closeChannel ? redisChannelName(options.closeChannel) : undefined;
  const subscribedChannels = [...new Set([channel, closeChannel].filter((value): value is string => !!value))];
  let closed = false;
  let deliveryQueue = Promise.resolve();

  const deliverResourceEvent = async (message: string): Promise<void> => {
    if (closed) return;
    if (options.authorize) {
      let authorized = false;
      try {
        authorized = await options.authorize();
      } catch (err) {
        logger.warn({ err, channel }, '[realtime] stream authorization check failed');
      }
      if (!authorized) {
        cleanup();
        return;
      }
    }
    if (!closed) res.write(`data: ${message}\n\n`);
  };

  const onMessage = (chan: string, msg: string): void => {
    if (closed) return;
    if (closeChannel && chan === closeChannel) {
      // The close channel is control-plane only. Do not expose its payload
      // on the resource stream; simply revoke this caller's connection.
      cleanup();
      return;
    }
    if (chan !== channel) return;
    if (!options.authorize) {
      // Preserve the existing synchronous delivery path for streams whose
      // authorization cannot change after they are opened.
      res.write(`data: ${msg}\n\n`);
      return;
    }
    // Serialize async authorization checks so resource events cannot be
    // reordered while a membership query is in flight.
    deliveryQueue = deliveryQueue
      .then(() => deliverResourceEvent(msg))
      .catch((err) => {
        logger.warn({ err, channel }, '[realtime] event delivery failed');
        cleanup();
      });
  };

  sub.on('message', onMessage);
  // Only the FIRST subscriber for each channel issues the Redis-level
  // SUBSCRIBE. Subsequent subscribers attach their listener to the shared
  // connection and increment the relevant refcounts independently.
  for (const subscribedChannel of subscribedChannels) {
    const previous = channelRefCount.get(subscribedChannel) ?? 0;
    channelRefCount.set(subscribedChannel, previous + 1);
    if (previous === 0) {
      sub.subscribe(subscribedChannel).catch((err) => {
        logger.warn({ err, channel: subscribedChannel }, '[realtime] subscribe failed');
        cleanup();
      });
    }
  }

  // Heartbeat so proxies don't drop the connection during idle
  const heartbeat = setInterval(() => {
    if (!closed) res.write(':heartbeat\n\n');
  }, 25_000);

  function cleanup(): void {
    if (closed) return;
    closed = true;
    clearInterval(heartbeat);
    sub.off('message', onMessage);
    for (const subscribedChannel of subscribedChannels) {
      const remaining = (channelRefCount.get(subscribedChannel) ?? 1) - 1;
      if (remaining <= 0) {
        channelRefCount.delete(subscribedChannel);
        sub.unsubscribe(subscribedChannel).catch(() => {});
      } else {
        channelRefCount.set(subscribedChannel, remaining);
      }
    }
    res.end();
  }

  req.on('close', cleanup);
  req.on('error', cleanup);
}
