import type { ReactNode } from "react";

export function Panel(props: { title?: string; right?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section
      className={`animate-fade-up rounded-2xl border border-white/5 bg-forge-panel shadow-card ${props.className ?? ""}`}
    >
      {(props.title || props.right) && (
        <header className="flex items-center justify-between gap-2 border-b border-white/5 px-4 py-3">
          <h2 className="text-sm font-semibold tracking-tight text-forge-text">{props.title}</h2>
          {props.right}
        </header>
      )}
      <div className="p-4">{props.children}</div>
    </section>
  );
}

export function Button(props: {
  children: ReactNode;
  onClick?: () => void;
  variant?: "primary" | "ghost" | "danger";
  disabled?: boolean;
  type?: "button" | "submit";
  className?: string;
}) {
  const v = props.variant ?? "ghost";
  const cls =
    v === "primary"
      ? "bg-gradient-to-b from-forge-accentHi to-forge-accent text-white shadow-glow hover:brightness-105 active:scale-[.98]"
      : v === "danger"
        ? "border border-white/5 bg-forge-panel2 text-forge-faint hover:text-red-400"
        : "border border-white/5 bg-forge-panel2 text-forge-body hover:border-white/10 hover:text-forge-text";
  return (
    <button
      type={props.type ?? "button"}
      onClick={props.onClick}
      disabled={props.disabled}
      className={`rounded-xl px-3.5 py-2 text-sm font-medium transition-all duration-150 disabled:cursor-not-allowed disabled:opacity-40 ${cls} ${props.className ?? ""}`}
    >
      {props.children}
    </button>
  );
}

export function Field(props: { label: string; required?: boolean; hint?: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 flex items-baseline gap-1 text-xs font-medium text-forge-muted">
        {props.label}
        {props.required && <span className="text-forge-accent">*</span>}
        {props.hint && <span className="ml-auto font-normal text-forge-faint">{props.hint}</span>}
      </span>
      {props.children}
    </label>
  );
}

export const inputCls =
  "w-full rounded-xl border border-white/5 bg-forge-panel2/70 px-3 py-2 text-sm text-forge-text placeholder:text-forge-faint transition-colors focus:border-forge-accent/40 focus:outline-none";

export function Badge(props: { children: ReactNode; tone?: "ok" | "err" | "muted" | "accent" }) {
  const tone =
    props.tone === "ok"
      ? "bg-forge-ok/10 text-forge-ok"
      : props.tone === "err"
        ? "bg-red-500/10 text-red-400"
        : props.tone === "accent"
          ? "bg-forge-accent/10 text-forge-accent"
          : "bg-forge-panel2 text-forge-muted";
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 font-mono text-[11px] font-medium ${tone}`}>
      {props.children}
    </span>
  );
}

// Semantic status dot — color carries meaning, no harsh badge chrome.
export function Dot(props: { tone?: "ok" | "warn" | "err" | "muted" }) {
  const c =
    props.tone === "ok"
      ? "bg-forge-ok shadow-[0_0_10px_rgba(52,211,153,0.45)]"
      : props.tone === "warn"
        ? "bg-forge-warn shadow-[0_0_10px_rgba(251,191,36,0.45)]"
        : props.tone === "err"
          ? "bg-red-400"
          : "bg-forge-faint";
  return <span className={`inline-block h-1.5 w-1.5 rounded-full ${c}`} />;
}

export function Logo(props: { size?: number }) {
  const s = props.size ?? 32;
  return (
    <img
      src="/logo.png"
      alt="Forge OS"
      width={s}
      height={s}
      className="rounded-xl shadow-glow"
      style={{ width: s, height: s }}
    />
  );
}
