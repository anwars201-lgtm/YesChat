import { generatedAppLocale } from '../generated/app-locale';

import { logger } from './logger';

interface SendResult {
  ok: boolean;
  skipped?: boolean;
  reason?: string;
  providerMessageId?: string;
}

interface MailPayload {
  to: string;
  subject: string;
  html: string;
  text?: string;
}

function isEnabled(): boolean {
  return (process.env.EMAIL_ENABLED ?? 'false').toLowerCase() === 'true';
}

async function sendViaResend(payload: MailPayload): Promise<SendResult> {
  const apiKey = process.env.RESEND_API_KEY;
  const from = process.env.EMAIL_FROM;
  if (!apiKey || !from) {
    return { ok: false, skipped: true, reason: 'RESEND_API_KEY or EMAIL_FROM not set' };
  }
  const res = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ from, to: payload.to, subject: payload.subject, html: payload.html, text: payload.text }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    logger.warn({ status: res.status, body }, 'resend send failed');
    return { ok: false, reason: `resend ${res.status}` };
  }
  const json = (await res.json().catch(() => ({}))) as { id?: string };
  return { ok: true, providerMessageId: json.id };
}

export async function sendEmail(payload: MailPayload): Promise<SendResult> {
  if (!isEnabled()) {
    logger.info({ to: payload.to, subject: payload.subject }, '[email] EMAIL_ENABLED=false → skipping send');
    return { ok: true, skipped: true, reason: 'EMAIL_ENABLED=false' };
  }
  return sendViaResend(payload);
}

/**
 * Transactional email copy per app language.
 *
 * These are the only user-facing strings the backend sends OUTSIDE a request,
 * so there is no `Accept-Language` to negotiate against — a mail arrives long
 * after the request that triggered it. The app's primary language is the
 * honest default: it is the language the account was created in.
 */
const EMAIL_COPY: Record<
  string,
  {
    verifySubject: string;
    verifyIntro: string;
    verifyCta: string;
    verifyIgnore: string;
    verifyText: (url: string) => string;
    resetSubject: string;
    resetIntro: string;
    resetCta: string;
    resetIgnore: string;
    resetText: (url: string) => string;
  }
> = {
  en: {
    verifySubject: 'Verify your email',
    verifyIntro: 'Welcome! Please confirm your email by clicking the link below:',
    verifyCta: 'Verify email',
    verifyIgnore: "If you didn't sign up, you can ignore this message.",
    verifyText: (url) => `Welcome! Verify your email: ${url}`,
    resetSubject: 'Reset your password',
    resetIntro: 'We received a request to reset your password. Click the link below to choose a new one:',
    resetCta: 'Reset password',
    resetIgnore: "This link expires in 1 hour. If you didn't request this, you can safely ignore this message.",
    resetText: (url) => `Reset your password: ${url} (expires in 1 hour). If you didn't request this, ignore this message.`,
  },
  sv: {
    verifySubject: 'Bekräfta din e-postadress',
    verifyIntro: 'Välkommen! Bekräfta din e-postadress genom att klicka på länken nedan:',
    verifyCta: 'Bekräfta e-postadress',
    verifyIgnore: 'Om du inte skapade ett konto kan du bortse från det här meddelandet.',
    verifyText: (url) => `Välkommen! Bekräfta din e-postadress: ${url}`,
    resetSubject: 'Återställ ditt lösenord',
    resetIntro: 'Vi har fått en begäran om att återställa ditt lösenord. Klicka på länken nedan för att välja ett nytt:',
    resetCta: 'Återställ lösenord',
    resetIgnore:
      'Länken går ut om en timme. Om du inte begärde detta kan du bortse från meddelandet.',
    resetText: (url) =>
      `Återställ ditt lösenord: ${url} (går ut om en timme). Om du inte begärde detta kan du bortse från meddelandet.`,
  },
  es: {
    verifySubject: 'Verifica tu correo electrónico',
    verifyIntro: '¡Bienvenido! Confirma tu correo electrónico haciendo clic en el enlace de abajo:',
    verifyCta: 'Verificar correo',
    verifyIgnore: 'Si no creaste una cuenta, puedes ignorar este mensaje.',
    verifyText: (url) => `¡Bienvenido! Verifica tu correo electrónico: ${url}`,
    resetSubject: 'Restablece tu contraseña',
    resetIntro: 'Recibimos una solicitud para restablecer tu contraseña. Haz clic en el enlace de abajo para elegir una nueva:',
    resetCta: 'Restablecer contraseña',
    resetIgnore: 'El enlace caduca en 1 hora. Si no lo solicitaste, puedes ignorar este mensaje.',
    resetText: (url) =>
      `Restablece tu contraseña: ${url} (caduca en 1 hora). Si no lo solicitaste, ignora este mensaje.`,
  },
  de: {
    verifySubject: 'Bestätige deine E-Mail-Adresse',
    verifyIntro: 'Willkommen! Bitte bestätige deine E-Mail-Adresse über den folgenden Link:',
    verifyCta: 'E-Mail bestätigen',
    verifyIgnore: 'Wenn du dich nicht registriert hast, kannst du diese Nachricht ignorieren.',
    verifyText: (url) => `Willkommen! Bestätige deine E-Mail-Adresse: ${url}`,
    resetSubject: 'Setze dein Passwort zurück',
    resetIntro: 'Wir haben eine Anfrage zum Zurücksetzen deines Passworts erhalten. Klicke auf den Link, um ein neues zu wählen:',
    resetCta: 'Passwort zurücksetzen',
    resetIgnore: 'Der Link läuft in 1 Stunde ab. Wenn du das nicht angefordert hast, ignoriere diese Nachricht.',
    resetText: (url) =>
      `Setze dein Passwort zurück: ${url} (läuft in 1 Stunde ab). Wenn du das nicht angefordert hast, ignoriere diese Nachricht.`,
  },
  fr: {
    verifySubject: 'Vérifiez votre adresse e-mail',
    verifyIntro: 'Bienvenue ! Confirmez votre adresse e-mail en cliquant sur le lien ci-dessous :',
    verifyCta: 'Vérifier l’adresse e-mail',
    verifyIgnore: "Si vous n'avez pas créé de compte, vous pouvez ignorer ce message.",
    verifyText: (url) => `Bienvenue ! Vérifiez votre adresse e-mail : ${url}`,
    resetSubject: 'Réinitialisez votre mot de passe',
    resetIntro:
      'Nous avons reçu une demande de réinitialisation de votre mot de passe. Cliquez sur le lien ci-dessous pour en choisir un nouveau :',
    resetCta: 'Réinitialiser le mot de passe',
    resetIgnore: "Ce lien expire dans 1 heure. Si vous n'êtes pas à l'origine de cette demande, ignorez ce message.",
    resetText: (url) =>
      `Réinitialisez votre mot de passe : ${url} (expire dans 1 heure). Si vous n'êtes pas à l'origine de cette demande, ignorez ce message.`,
  },
};

function copy(): (typeof EMAIL_COPY)['en'] {
  const locale = (generatedAppLocale || process.env.APP_LOCALE || 'en').toLowerCase().split('-')[0].split('_')[0];
  return EMAIL_COPY[locale] ?? EMAIL_COPY.en;
}

export async function sendVerificationEmail(to: string, verifyUrl: string): Promise<SendResult> {
  const t = copy();
  return sendEmail({
    to,
    subject: t.verifySubject,
    html: `<p>${t.verifyIntro}</p><p><a href="${verifyUrl}">${t.verifyCta}</a></p><p>${t.verifyIgnore}</p>`,
    text: t.verifyText(verifyUrl),
  });
}

export async function sendPasswordResetEmail(to: string, resetUrl: string): Promise<SendResult> {
  const t = copy();
  return sendEmail({
    to,
    subject: t.resetSubject,
    html: `<p>${t.resetIntro}</p><p><a href="${resetUrl}">${t.resetCta}</a></p><p>${t.resetIgnore}</p>`,
    text: t.resetText(resetUrl),
  });
}
