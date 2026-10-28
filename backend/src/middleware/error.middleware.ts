import { Request, Response, NextFunction } from 'express';

import { generatedAppLocale, generatedAppLocales } from '../generated/app-locale';
import { logger } from '../lib/logger';

export class AppError extends Error {
  constructor(
    public statusCode: number,
    message: string,
    public code?: string,
  ) {
    super(message);
    this.name = 'AppError';
  }
}

/**
 * Friendly end-user copy per backend locale. The app's chosen language is
 * locked into compiler-owned source by Product Contract V2. APP_LOCALE stays
 * as a legacy/runtime compatibility fallback. English is the final fallback,
 * so a missing entry never leaks a raw exception.
 */
const FRIENDLY_MESSAGES: Record<string, Record<string, string>> = {
  en: {
    'prisma.unique': 'A record with that value already exists.',
    'prisma.notFound': 'The record could not be found.',
    'prisma.fkViolation': 'This record is used elsewhere and cannot be removed.',
    'prisma.required': 'A required field is missing.',
    'jwt.expired': 'Your session has expired. Please log in again.',
    'jwt.invalid': 'Invalid credentials.',
    validation: 'The request contains invalid data.',
    unexpected: 'Something went wrong. Please try again.',
  },
  sv: {
    'prisma.unique': 'Det finns redan en post med det värdet.',
    'prisma.notFound': 'Posten kunde inte hittas.',
    'prisma.fkViolation': 'Posten används någon annanstans och kan inte tas bort.',
    'prisma.required': 'Ett obligatoriskt fält saknas.',
    'jwt.expired': 'Din session har gått ut. Logga in igen.',
    'jwt.invalid': 'Felaktiga inloggningsuppgifter.',
    validation: 'Begäran innehåller ogiltiga data.',
    unexpected: 'Något gick fel. Försök igen.',
  },
  es: {
    'prisma.unique': 'Ya existe un registro con ese valor.',
    'prisma.notFound': 'No se encontró el registro.',
    'prisma.fkViolation': 'Este registro está en uso y no se puede eliminar.',
    'prisma.required': 'Falta un campo obligatorio.',
    'jwt.expired': 'Tu sesión ha caducado. Vuelve a iniciar sesión.',
    'jwt.invalid': 'Credenciales no válidas.',
    validation: 'La solicitud contiene datos no válidos.',
    unexpected: 'Algo salió mal. Inténtalo de nuevo.',
  },
  de: {
    'prisma.unique': 'Es existiert bereits ein Eintrag mit diesem Wert.',
    'prisma.notFound': 'Der Eintrag wurde nicht gefunden.',
    'prisma.fkViolation': 'Dieser Eintrag wird verwendet und kann nicht gelöscht werden.',
    'prisma.required': 'Ein Pflichtfeld fehlt.',
    'jwt.expired': 'Deine Sitzung ist abgelaufen. Bitte erneut anmelden.',
    'jwt.invalid': 'Ungültige Anmeldedaten.',
    validation: 'Die Anfrage enthält ungültige Daten.',
    unexpected: 'Etwas ist schiefgelaufen. Bitte erneut versuchen.',
  },
  fr: {
    'prisma.unique': 'Un enregistrement avec cette valeur existe déjà.',
    'prisma.notFound': "L'enregistrement est introuvable.",
    'prisma.fkViolation': 'Cet enregistrement est utilisé et ne peut pas être supprimé.',
    'prisma.required': 'Un champ obligatoire est manquant.',
    'jwt.expired': 'Votre session a expiré. Veuillez vous reconnecter.',
    'jwt.invalid': 'Identifiants invalides.',
    validation: 'La requête contient des données non valides.',
    unexpected: "Une erreur s'est produite. Veuillez réessayer.",
  },
};

function baseLanguage(tag: string): string {
  return tag.toLowerCase().split('-')[0].split('_')[0];
}

/**
 * Pick the language to answer this request in.
 *
 * Single-locale apps keep the compiled locale unconditionally. A multi-locale
 * app follows the device the same way the client does, which it can only learn
 * from `Accept-Language` — otherwise an English reader of a Swedish+English app
 * gets Swedish error copy. Unknown or unshipped languages fall back to the
 * app's primary, then to English, so a malformed header never leaks anything.
 */
function resolveRequestLocale(req?: Request): string {
  const primary = baseLanguage(generatedAppLocale || process.env.APP_LOCALE || 'en');
  const shipped = generatedAppLocales.map(baseLanguage);
  if (shipped.length < 2) return primary;

  const header = req?.headers?.['accept-language'];
  if (typeof header !== 'string') return primary;
  const preferences = header
    .split(',')
    .map((part) => {
      const [tag, ...params] = part.trim().split(';');
      const quality = params.find((param) => param.trim().startsWith('q='));
      const parsed = quality ? Number.parseFloat(quality.trim().slice(2)) : 1;
      return { language: baseLanguage(tag), quality: Number.isFinite(parsed) ? parsed : 0 };
    })
    .filter((preference) => preference.language && preference.quality > 0)
    .sort((left, right) => right.quality - left.quality);

  return preferences.find((preference) => shipped.includes(preference.language))?.language ?? primary;
}

function friendly(key: string, req?: Request): string {
  const locale = resolveRequestLocale(req);
  return FRIENDLY_MESSAGES[locale]?.[key] || FRIENDLY_MESSAGES.en[key] || FRIENDLY_MESSAGES.en.unexpected;
}

type PrismaLikeError = Error & { code?: string; meta?: Record<string, unknown> };

/**
 * Map a Prisma error code to a friendly message + http status. Returns
 * `null` if the error isn't a recognised Prisma failure.
 */
function mapPrismaError(
  err: PrismaLikeError,
  req?: Request,
): { statusCode: number; code: string; message: string } | null {
  if (!err.code || !err.code.startsWith('P')) return null;
  switch (err.code) {
    case 'P2002':
      return { statusCode: 409, code: 'UNIQUE_VIOLATION', message: friendly('prisma.unique', req) };
    case 'P2025':
      return { statusCode: 404, code: 'NOT_FOUND', message: friendly('prisma.notFound', req) };
    case 'P2003':
      return { statusCode: 409, code: 'FK_VIOLATION', message: friendly('prisma.fkViolation', req) };
    case 'P2011':
    case 'P2012':
    case 'P2013':
      return { statusCode: 400, code: 'REQUIRED_FIELD', message: friendly('prisma.required', req) };
    default:
      return null;
  }
}

export function errorMiddleware(err: Error, req: Request, res: Response, _next: NextFunction): void {
  const log = (req as Request & { log?: typeof logger }).log || logger;
  const userId = (req as Request & { user?: { id?: string } }).user?.id;

  if (err instanceof AppError) {
    log.warn(
      { err, method: req.method, url: req.originalUrl, statusCode: err.statusCode, code: err.code, userId },
      'Request failed with AppError',
    );
    res.status(err.statusCode).json({ error: err.message, code: err.code });
    return;
  }

  // JWT errors — produced by `jsonwebtoken` with a distinct `name`.
  if (err.name === 'TokenExpiredError') {
    log.warn({ method: req.method, url: req.originalUrl, userId }, 'JWT expired');
    res.status(401).json({ error: friendly('jwt.expired', req), code: 'TOKEN_EXPIRED' });
    return;
  }
  if (err.name === 'JsonWebTokenError') {
    log.warn({ method: req.method, url: req.originalUrl, userId }, 'JWT invalid');
    res.status(401).json({ error: friendly('jwt.invalid', req), code: 'TOKEN_INVALID' });
    return;
  }

  // Zod validation errors — `schema.parse()` throws a ZodError when the request
  // body is invalid (e.g. a create with a missing required field). Name-checked
  // so the middleware needn't import zod. Return 400 with the offending field
  // paths instead of a generic 500, so a bad/incomplete request gets an
  // actionable message rather than "Something went wrong".
  if (err.name === 'ZodError') {
    const issues = (err as Error & { issues?: Array<{ path?: Array<string | number>; message?: string }> }).issues ?? [];
    const fields = issues
      .map((i) => (Array.isArray(i.path) ? i.path.join('.') : ''))
      .filter((p): p is string => p.length > 0);
    log.warn({ method: req.method, url: req.originalUrl, fields, userId }, 'Request failed validation');
    res.status(400).json({ error: friendly('validation', req), code: 'VALIDATION_ERROR', fields });
    return;
  }

  const prismaMapped = mapPrismaError(err as PrismaLikeError, req);
  if (prismaMapped) {
    log.warn(
      {
        err,
        method: req.method,
        url: req.originalUrl,
        prismaCode: (err as PrismaLikeError).code,
        userId,
      },
      'Prisma error mapped to friendly response',
    );
    res.status(prismaMapped.statusCode).json({ error: prismaMapped.message, code: prismaMapped.code });
    return;
  }

  log.error({ err, method: req.method, url: req.originalUrl, userId }, 'Unhandled error');
  res.status(500).json({ error: friendly('unexpected', req), code: 'INTERNAL_ERROR' });
}
