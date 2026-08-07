import { useState } from "react";
import { connect } from "../service";
import { connectionStore, setConnection, type Mode } from "../store/connection";
import { useStore } from "../store/store";
import { Button, Dot, Field, inputCls, Panel } from "../components/ui";

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
    <div className="mx-auto flex min-h-[76vh] max-w-md flex-col items-center justify-center animate-fade-up">
      {/* Hero branding */}
      <div className="mb-8 flex flex-col items-center text-center">
        <img src="/logo.png" alt="Forge OS" className="h-16 w-16 rounded-2xl shadow-glow" />
        <h1 className="mt-4 text-2xl font-semibold tracking-tight">
          Forge <span className="text-forge-accent">Dev UI</span>
        </h1>
        <p className="mt-1.5 text-sm text-forge-faint">
          Iterate on the tool &amp; chat surface without rebuilding the app.
        </p>
      </div>

      <Panel className="w-full" title="Connection">
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-2.5">
            <ModeButton active={conn.mode === "mock"} onClick={() => setMode("mock")}
              title="Mock" desc="In-browser fake server. No device needed." />
            <ModeButton active={conn.mode === "live"} onClick={() => setMode("live")}
              title="Live" desc="Talk to a device or mock_server.py." />
          </div>

          {conn.mode === "live" && (
            <div className="grid grid-cols-3 gap-3 rounded-xl border border-white/5 bg-forge-panel2/50 p-3">
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
              {conn.status === "connected" && <><Dot tone="ok" /><span className="text-xs text-forge-muted">connected</span></>}
              {conn.status === "connecting" && <><Dot tone="warn" /><span className="text-xs text-forge-muted">connecting…</span></>}
              {conn.status === "disconnected" && <><Dot /><span className="text-xs text-forge-faint">disconnected</span></>}
              {conn.status === "error" && <><Dot tone="err" /><span className="text-xs text-red-400">error</span></>}
            </div>
            <Button variant="primary" onClick={onConnect} disabled={busy || conn.status === "connecting"} className="min-w-28">
              {conn.status === "connected" ? "Reconnect" : busy ? "Connecting…" : "Connect"}
            </Button>
          </div>

          {conn.status === "error" && conn.error && (
            <p className="rounded-xl border border-red-500/20 bg-red-500/5 px-3 py-2 text-xs text-red-400">{conn.error}</p>
          )}

          {conn.mode === "live" && (
            <p className="text-xs leading-relaxed text-forge-faint">
              Tip: run{" "}
              <code className="rounded bg-forge-panel2 px-1.5 py-0.5 font-mono text-forge-accentSoft">
                python forge-desktop/mock_server.py 8789
              </code>{" "}
              for a local server.
            </p>
          )}
        </div>
      </Panel>

      <p className="mt-8 text-[11px] font-medium text-forge-faint/70">Forge OS · on-device AI automation</p>
    </div>
  );
}

function ModeButton(props: { active: boolean; onClick: () => void; title: string; desc: string }) {
  return (
    <button
      onClick={props.onClick}
      className={`rounded-xl border p-3.5 text-left transition-all duration-150 ${
        props.active
          ? "border-forge-accent/30 bg-forge-accent/5"
          : "border-white/5 bg-forge-panel2/40 hover:border-white/10"
      }`}
    >
      <div className={`text-sm font-semibold ${props.active ? "text-forge-accent" : "text-forge-text"}`}>
        {props.title}
      </div>
      <div className="mt-1 text-xs leading-relaxed text-forge-faint">{props.desc}</div>
    </button>
  );
}
