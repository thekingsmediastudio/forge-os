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

const TABS: Array<{ id: Tab; label: string }> = [
  { id: "tools", label: "Tools" },
  { id: "editor", label: "Editor" },
  { id: "chat", label: "Chat" },
  { id: "history", label: "History" },
];

export default function App() {
  const conn = useStore(connectionStore);
  const [tab, setTab] = useState<Tab>("tools");

  if (conn.status !== "connected") {
    return (
      <Shell
        conn={
          <span className="flex items-center gap-2 text-xs text-forge-faint">
            <Dot /> offline
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
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-2 text-xs text-forge-muted">
            <Dot tone="ok" />
            {conn.mode === "mock" ? "mock" : `${conn.cfg.host}:${conn.cfg.port}`}
          </span>
          <button
            onClick={disconnect}
            className="text-xs font-medium text-forge-faint transition-colors hover:text-forge-text"
          >
            Disconnect
          </button>
        </div>
      }
      tabs={
        <nav className="flex gap-1 rounded-full border border-white/5 bg-forge-panel/70 p-1">
          {TABS.map((t) => (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              className={`rounded-full px-3.5 py-1.5 text-sm font-medium transition-all duration-150 ${
                tab === t.id ? "bg-forge-panel2 text-forge-text shadow-card" : "text-forge-faint hover:text-forge-body"
              }`}
            >
              {t.label}
            </button>
          ))}
        </nav>
      }
    >
      {tab === "tools" && <ToolsView />}
      {tab === "editor" && <ToolEditor />}
      {tab === "chat" && <ChatView />}
      {tab === "history" && <HistoryView />}
    </Shell>
  );
}

function Shell(props: { children: React.ReactNode; conn?: React.ReactNode; tabs?: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-10 border-b border-white/5 bg-forge-bg/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-6xl items-center gap-3 px-6 py-3">
          <div className="flex items-center gap-3">
            <Logo size={32} />
            <div className="leading-tight">
              <div className="text-sm font-semibold tracking-tight">Forge OS</div>
              <div className="text-[11px] text-forge-faint">Dev UI</div>
            </div>
          </div>
          <div className="flex flex-1 justify-center">{props.tabs}</div>
          <div className="flex items-center">{props.conn}</div>
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col px-6 py-6">{props.children}</main>
    </div>
  );
}
