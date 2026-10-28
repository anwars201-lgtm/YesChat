/**
 * Compiler-owned locale for backend error copy.
 *
 * Product Contract V2 replaces this source file during deterministic
 * materialization. The nullable default preserves legacy projects, where the
 * runtime APP_LOCALE environment value remains the compatibility fallback.
 */
export const generatedAppLocale: string | null = null;

/**
 * Every language the app ships, primary first.
 *
 * Empty means single-locale (or a legacy project): callers keep using
 * `generatedAppLocale` alone. When the user selected more than one language
 * the backend cannot know which one the caller reads without asking, so this
 * list is what `Accept-Language` is negotiated against.
 */
export const generatedAppLocales: string[] = [];
