/**
 * Public base URL for absolute links (email verify/reset links, uploaded-file
 * URLs). Derived ONLY from server-side config — NEVER from the request Host
 * header, which an attacker can forge to poison reset/verify links and steal
 * the token (password-reset poisoning -> account takeover). Returns null when
 * unconfigured so callers can decide to skip or fall back to a relative path
 * rather than emit a poisoned or broken link.
 */
export function getPublicBaseUrl(): string | null {
  const raw = process.env.PUBLIC_BASE_URL?.trim();
  return raw ? raw.replace(/\/+$/, '') : null;
}
