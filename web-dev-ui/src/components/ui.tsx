import type { ReactNode } from "react";

/* ── Panel ─────────────────────────────────────────────────────────────────
   Glassy card with a top sheen, soft inner highlight, and a border that
   warms toward the accent on hover. */
export function Panel(props: {
  title?: string;
  right?: ReactNode;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section
      className={`group/panel animate-fade-up rounded-2xl border border-white/[0.06] bg-forge-panel/80 bg-panel-sheen shadow-card backdrop-blur-sm transition-colors duration-200 hover:border-white/10 ${props.className ?? ""}`}
    >
      {(props.title || props.right) && (
        <header className="flex items-center justify-between gap-2 border-b border-white/[0.06] px-4 py-3">
          <h2 className="flex items-center gap-2 text-sm font-semibold tracking-tight text-forge-text">
            <span className="inline-block h-3 w-[3px] rounded-full bg-accent-grad opacity-80" />
            {props.title}
          </h2>
          {props.right}
        </header>
      )}
      <div className={props.bodyClassName ?? "p-4"}>{props.children}</div>
    </section>
  );
}

/* ── Button ─────────────────────────────────────────────────────────────── */
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
      ? "bg-accent-grad text-white shadow-glow shadow-inner-hi hover:shadow-glow-lg hover:brightness-110 active:scale-[.97]"
      : v === "danger"
        ? "border border-white/[0.06] bg-forge-panel2 text-forge-faint hover:border-forge-danger/30 hover:bg-forge-danger/10 hover:text-forge-danger"
        : "border border-white/[0.06] bg-forge-panel2 text-forge-body hover:border-white/[0.14] hover:bg-forge-panel3 hover:text-forge-text";
  return (
    <button
      type={props.type ?? "button"}
      onClick={props.onClick}
      disabled={props.disabled}
      className={`inline-flex items-center justify-center gap-2 rounded-xl px-3.5 py-2 text-sm font-medium transition-all duration-150 disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none ${cls} ${props.className ?? ""}`}
    >
      {props.children}
    </button>
  );
}

/* ── Field + input ──────────────────────────────────────────────────────── */
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
  "w-full rounded-xl border border-white/[0.07] bg-forge-bg/70 px-3 py-2 text-sm text-forge-text shadow-inner placeholder:text-forge-faint transition-all duration-150 hover:border-white/[0.12] focus:border-forge-accent/50 focus:bg-forge-bg focus:shadow-[0_0_0_3px_rgba(255,107,61,0.12)] focus:outline-none";

/* ── Badge ──────────────────────────────────────────────────────────────── */
export function Badge(props: { children: ReactNode; tone?: "ok" | "err" | "muted" | "accent" }) {
  const tone =
    props.tone === "ok"
      ? "border-forge-ok/20 bg-forge-ok/10 text-forge-ok"
      : props.tone === "err"
        ? "border-forge-danger/20 bg-forge-danger/10 text-forge-danger"
        : props.tone === "accent"
          ? "border-forge-accent/25 bg-forge-accent/10 text-forge-accentSoft"
          : "border-white/[0.06] bg-forge-panel2 text-forge-muted";
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 font-mono text-[11px] font-medium ${tone}`}
    >
      {props.children}
    </span>
  );
}

/* ── Status dot (ok state pulses gently) ────────────────────────────────── */
export function Dot(props: { tone?: "ok" | "warn" | "err" | "muted"; pulse?: boolean }) {
  const c =
    props.tone === "ok"
      ? "bg-forge-ok shadow-[0_0_10px_rgba(52,211,153,0.5)]"
      : props.tone === "warn"
        ? "bg-forge-warn shadow-[0_0_10px_rgba(251,191,36,0.5)]"
        : props.tone === "err"
          ? "bg-forge-danger shadow-[0_0_10px_rgba(248,113,113,0.5)]"
          : "bg-forge-faint";
  const pulse = props.pulse ?? props.tone === "ok" ? "animate-pulse-dot" : "";
  return <span className={`inline-block h-1.5 w-1.5 rounded-full ${c} ${pulse}`} />;
}

/* ── Logo ───────────────────────────────────────────────────────────────── */
export function Logo(props: { size?: number; glow?: boolean }) {
  const s = props.size ?? 32;
  return (
    <img
      src="/logo.png"
      alt="Forge OS"
      width={s}
      height={s}
      className={`rounded-xl ${props.glow === false ? "shadow-glow" : "animate-logo-glow"}`}
      style={{ width: s, height: s }}
    />
  );
}

/* ── Spinner ────────────────────────────────────────────────────────────── */
export function Spinner(props: { size?: number; className?: string }) {
  const s = props.size ?? 14;
  return (
    <span
      className={`inline-block animate-spin rounded-full border-2 border-forge-accent/25 border-t-forge-accent ${props.className ?? ""}`}
      style={{ width: s, height: s }}
      role="status"
      aria-label="loading"
    />
  );
}

/* ── EmptyState ─────────────────────────────────────────────────────────── */
export function EmptyState(props: { title: string; hint?: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-1.5 px-6 py-12 text-center">
      <div className="mb-1 flex h-10 w-10 items-center justify-center rounded-2xl border border-white/[0.06] bg-forge-panel2 shadow-inner-hi">
        <span className="h-2 w-2 rounded-full bg-forge-faint" />
      </div>
      <p className="text-sm font-medium text-forge-body">{props.title}</p>
      {props.hint && <p className="max-w-sm text-xs leading-relaxed text-forge-faint">{props.hint}</p>}
      {props.children}
    </div>
  );
}
