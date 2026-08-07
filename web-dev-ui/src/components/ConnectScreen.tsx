import { useState } from "react";
import { connect } from "../service";
import { connectionStore, setConnection, type Mode } from "../store/connection";
import { useStore } from "../store/store";
import { Button, Dot, Field, inputCls, Logo, Panel, Spinner } from "../components/ui";

export default function ConnectScreen() {
  const conn = useStore(connectionStore);
  const [busy, setBusy] = useState(false);

  const setMode = (mode: Mode) => setConnection({ mode, status: "disconnected", error: null });

  const onConnect = async () => {
    setBusy(true);
    try {
      await connect();
    } catch {
      /* error state set in store */
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto my-auto flex w-full max-w-md flex-col items-center justify-center">
      {/* Hero branding with animated glow */}
      <div className="animate-fade-up mb-8 flex flex-col items-center text-center" style={{ animationDelay: "0ms" }}>
        <div className="relative">
          <div className="absolute inset-0 -m-4 rounded-[28px] bg-forge-accent/15 blur-2xl" />
          <Logo size={68} />
        </div>
        <h1 className="mt-5 text-[26px] font-semibold tracking-tightest">
          Forge <span className="bg-accent-grad bg-clip-text text-transparent">Dev UI</span>
        </h1>
        <p className="mt-2 max-w-xs text-sm leading-relaxed text-forge-faint">
          Iterate on the tool &amp; chat surface without rebuilding the app.
        </p>
      </div>

      <div className="animate-fade-up w-full" style={{ animationDelay: "70ms" }}>
        <Panel title="Connection">
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-2.5">
              <ModeButton active={conn.mode === "mock"} onClick={() => setMode("mock")}
                title="Mock" desc="In-browser fake server. No device needed." />
              <ModeButton active={conn.mode === "live"} onClick={() => setMode("live")}
                title="Live" desc="Talk to a device or mock_server.py." />
            </div>

            {conn.mode === "live" && (
              <div className="animate-fade-up grid grid-cols-3 gap-3 rounded-xl border border-white/[0.06] bg-forge-bg/50 p-3 shadow-inner">
                <div className="col-span-2">
                  <Field label="Host">
                    <input
                      className={inputCls}
                      value={conn.cfg.host}
                      onChange={(e) => setConnection({ cfg: { ...conn.cfg, host: e.target.value } })}
                      placeholder="127.0.0.1 or phone IP"
                    />
                  </Field>
                </div>
                <Field label="Port">
                  <input
                    className={inputCls}
                    type="number"
                    value={conn.cfg.port}
                    onChange={(e) => setConnection({ cfg: { ...conn.cfg, port: Number(e.target.value) || 0 } })}
                  />
                </Field>
                <div className="col-span-3">
                  <Field label="API token" hint="Bearer">
                    <input
                      className={inputCls}
                      type="password"
                      value={conn.cfg.token}
                      onChange={(e) => setConnection({ cfg: { ...conn.cfg, token: e.target.value } })}
                      placeholder="test-token"
                    />
                  </Field>
                </div>
              </div>
            )}

            <div className="flex items-center justify-between pt-1">
              <div className="flex items-center gap-2">
                {conn.status === "connected" && <><Dot tone="ok" /><span className="text-xs font-medium text-forge-ok">connected</span></>}
                {conn.status === "connecting" && <><Dot tone="warn" /><span className="text-xs text-forge-warn">connecting…</span></>}
                {conn.status === "disconnected" && <><Dot pulse={false} /><span className="text-xs text-forge-faint">disconnected</span></>}
                {conn.status === "error" && <><Dot tone="err" /><span className="text-xs text-forge-danger">error</span></>}
              </div>
              <Button variant="primary" onClick={onConnect} disabled={busy || conn.status === "connecting"} className="min-w-28">
                {busy || conn.status === "connecting" ? (
                  <><Spinner size={13} className="border-white/30 border-t-white" /> Connecting</>
                ) : conn.status === "connected" ? "Reconnect" : "Connect"}
              </Button>
            </div>

            {conn.status === "error" && conn.error && (
              <p className="animate-fade-up rounded-xl border border-forge-danger/25 bg-forge-danger/[0.08] px-3 py-2 text-xs leading-relaxed text-forge-danger">
                {conn.error}
              </p>
            )}

            {conn.mode === "live" && (
              <p className="text-xs leading-relaxed text-forge-faint">
                Tip: run{" "}
                <code className="rounded-md border border-white/[0.06] bg-forge-panel2 px-1.5 py-0.5 font-mono text-[11px] text-forge-accentSoft">
                  python forge-desktop/mock_server.py 8789
                </code>{" "}
                for a local server.
              </p>
            )}
          </div>
        </Panel>
      </div>

      <p className="animate-fade-up mt-7 text-[11px] font-medium tracking-wide text-forge-faint/60" style={{ animationDelay: "140ms" }}>
        Forge OS · on-device AI automation
      </p>
    </div>
  );
}

function ModeButton(props: { active: boolean; onClick: () => void; title: string; desc: string }) {
  return (
    <button
      onClick={props.onClick}
      className={`group rounded-xl border p-3.5 text-left transition-all duration-200 ${
        props.active
          ? "border-forge-accent/35 bg-forge-accent/[0.07] shadow-[0_0_0_1px_rgba(255,107,61,0.15),0_8px_20px_rgba(255,107,61,0.08)]"
          : "border-white/[0.06] bg-forge-panel2/40 hover:border-white/[0.12] hover:bg-forge-panel2/70"
      }`}
    >
      <div className="flex items-center gap-2">
        <span
          className={`flex h-3.5 w-3.5 items-center justify-center rounded-full border transition-colors ${
            props.active ? "border-forge-accent bg-forge-accent" : "border-forge-faint/50 group-hover:border-forge-muted"
          }`}
        >
          {props.active && <span className="h-1.5 w-1.5 rounded-full bg-white" />}
        </span>
        <div className={`text-sm font-semibold ${props.active ? "text-forge-accent" : "text-forge-text"}`}>
          {props.title}
        </div>
      </div>
      <div className="mt-1.5 pl-[22px] text-xs leading-relaxed text-forge-faint">{props.desc}</div>
    </button>
  );
}
