/**
 * Session state: the API bearer token.
 *
 * The token is held in `sessionStorage`, never `localStorage` and never a cookie.
 * That is a security decision, not an implementation detail: it means the credential
 * lives for exactly one tab's lifetime, is not sent ambiently by the browser on
 * cross-site requests, and cannot be read by a script loaded in another tab or after
 * the tab closes. See docs/security.md and docs/adr/0005-auth-model.md.
 */

const TOKEN_KEY = "repopilot.token";

function storage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    // Storage can be unavailable (privacy modes, sandboxed iframes). Failing to
    // persist the token is acceptable; the app still works for this page load.
    return null;
  }
}

export function getToken(): string | null {
  const value = storage()?.getItem(TOKEN_KEY) ?? null;
  return value && value.trim().length > 0 ? value : null;
}

export function setToken(token: string): void {
  storage()?.setItem(TOKEN_KEY, token.trim());
}

export function clearToken(): void {
  storage()?.removeItem(TOKEN_KEY);
}
