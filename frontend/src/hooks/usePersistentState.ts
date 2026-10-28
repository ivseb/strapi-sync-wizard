import { Dispatch, SetStateAction, useEffect, useState } from 'react';

/**
 * useState remembered in localStorage under [key] (per-viewer convenience: filters, collapsed groups).
 * Storage may be unavailable (private mode, blocked site data): every access is guarded and the
 * state simply falls back to [initial].
 */
export function usePersistentState<T>(
  key: string,
  initial: T,
  serialize: (v: T) => unknown = (v) => v,
  deserialize: (raw: any) => T = (raw) => raw as T,
): [T, Dispatch<SetStateAction<T>>] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = window.localStorage.getItem(key);
      if (raw != null) return deserialize(JSON.parse(raw));
    } catch { /* storage unavailable or corrupt: use the default */ }
    return initial;
  });
  useEffect(() => {
    try { window.localStorage.setItem(key, JSON.stringify(serialize(value))); } catch { /* ignore */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, value]);
  return [value, setValue];
}

/** Same, for a Set (stored as an array). */
export function usePersistentSet<T>(key: string, initial: Set<T>): [Set<T>, Dispatch<SetStateAction<Set<T>>>] {
  return usePersistentState<Set<T>>(key, initial, (s) => Array.from(s), (raw) => new Set(Array.isArray(raw) ? raw : []));
}
