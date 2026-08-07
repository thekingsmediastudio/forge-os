import { useState } from "react";
import ChatView from "./components/ChatView";
import ConnectScreen from "./components/ConnectScreen";
import HistoryView from "./components/HistoryView";
import ToolEditor from "./components/ToolEditor";
import ToolsView from "./components/ToolsView";
import { Dot, Logo } from "./components/ui";
import { disconnect } from "./service";
import { connectionStore } from "./store/connection";
import { useStore } from "./store/store";

type Tab = "tools" | "editor" | "chat" | "history";

const TABS: Array<{ id: Tab; label: string; glyph: string }> = [
  { id: "tools", label: "Tools", glyph: "⚒" },
  { id: "editor", label: "Editor", glyph: "✎" },
  { id: "chat", label: "Chat", glyph: "◆" },
  { id: "history", label: "History", glyph: "↺" },
];

export default function App() {
  const conn = useStore(connectionStore);
  const [tab, setTab] = useState<Tab>("tools");

  if (conn.status !== "connected") {
    return (
      <Shell
        conn={
          <span className="flex items-center gap-2 rounded-full border border-white/[0.06] bg-forge-panel/70 px-3 py-1.5 text-xs text-forge-faint">
            <Dot pulse={false} /> offline
          </span>
        }
      >
        <ConnectScreen />
      </Shell>
    );
  }

  return (
    <Shell
      conn={
        <div className="flex items-center gap-2.5">
          <span className="flex items-center gap-2 rounded-full border border-forge-ok/20 bg-forge-ok/[0.07] px-3 py-1.5 text-xs font-medium text-forge-ok">
            <Dot tone="ok" />
            {conn.mode === "mock" ? "mock" : `${conn.cfg.host}:${conn.cfg.port}`}
          </span>
          <button
            onClick={disconnect}
            className="rounded-full px-2.5 py-1.5 text-xs font-medium text-forge-faint transition-colors duration-150 hover:bg-forge-panel2 hover:text-forge-text"
          >
            Disconnect
          </button>
        </div>
      }
      tabs={
        <nav className="flex gap-0.5 rounded-full border border-white/[0.06] bg-forge-panel/80 p-1 shadow-inner-hi backdrop-blur-sm">
          {TABS.map((t) => {
            const active = tab === t.id;
            return (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                className={`relative flex items-center gap-1.5 rounded-full px-3.5 py-1.5 text-sm font-medium transition-all duration-200 ${
                  active
                    ? "bg-forge-panel3 text-forge-text shadow-pop"
                    : "text-forge-faint hover:text-forge-body"
                }`}
              >
                <span className={`text-[11px] leading-none ${active ? "text-forge-accent" : "text-forge-faint/70"}`}>
                  {t.glyph}
                </span>
                {t.label}
                {active && (
                  <span className="absolute inset-x-3 -bottom-[5px] h-px bg-accent-grad opacity-80" />
                )}
              </button>
            );
          })}
        </nav>
      }
    >
      <div key={tab} className="animate-fade-up">
        {tab === "tools" && <ToolsView />}
        {tab === "editor" && <ToolEditor />}
        {tab === "chat" && <ChatView />}
        {tab === "history" && <HistoryView />}
      </div>
    </Shell>
  );
}

function Shell(props: { children: React.ReactNode; conn?: React.ReactNode; tabs?: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-20 border-b border-white/[0.06] bg-forge-bg/75 backdrop-blur-xl">
        <div className="mx-auto flex max-w-7xl items-center gap-3 px-6 py-3">
          <div className="flex items-center gap-3">
            <Logo size={32} />
            <div className="leading-tight">
              <div className="text-sm font-semibold tracking-tightest">Forge OS</div>
              <div className="text-[11px] font-medium tracking-wide text-forge-faint">Dev UI</div>
            </div>
          </div>
          <div className="flex flex-1 justify-center">{props.tabs}</div>
          <div className="flex items-center">{props.conn}</div>
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-7xl flex-1 flex-col px-6 py-6">{props.children}</main>
    </div>
  );
}
