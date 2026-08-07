import { useSyncExternalStore } from "react";

// Minimal external-store hook (no state lib). A store holds a value and a set
// of listeners; useStore subscribes a component to it.

type Listener = () => void;

export class Store<T> {
  private value: T;
  private listeners = new Set<Listener>();

  constructor(initial: T) {
    this.value = initial;
  }

  get(): T {
    return this.value;
  }

  set(next: T): void {
    this.value = next;
    this.listeners.forEach((l) => l());
  }

  update(fn: (prev: T) => T): void {
    this.set(fn(this.value));
  }

  subscribe = (l: Listener): (() => void) => {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  };

  getSnapshot = (): T => this.value;
}

export function useStore<T>(store: Store<T>): T {
  return useSyncExternalStore(store.subscribe, store.getSnapshot);
}

export function loadJSON<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

export function saveJSON(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* storage full / unavailable — non-fatal */
  }
}
