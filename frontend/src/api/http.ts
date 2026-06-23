import axios from 'axios';

/**
 * Single axios instance for the whole app. Relative URLs are proxied to the Ktor backend
 * (see vite.config.ts dev proxy; in production the app is served by the backend itself).
 */
export const http = axios.create({
  headers: { 'Content-Type': 'application/json' },
});

// Normalize backend errors (plain-text or {error/message}) into an Error with a readable message.
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const data = error?.response?.data;
    const message =
      (typeof data === 'string' && data) ||
      data?.message ||
      data?.error?.message ||
      error?.message ||
      'Request failed';
    return Promise.reject(new Error(message));
  }
);

/** Extract a human-readable message from anything thrown by the API layer. */
export function apiErrorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
