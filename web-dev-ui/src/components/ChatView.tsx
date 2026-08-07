import { useEffect, useRef, useState } from "react";
import { sendChat } from "../service";
import type { ChatMessage } from "../types";
import { Badge, Button, inputCls, Panel } from "../components/ui";

// Chat against the device ReActAgent (live) or the in-browser mock. Replies
// from the server fold tool calls into the text as "⚙ <tool>" lines (see
// ForgeHttpServer.runChatTurn) — we render those as chips for readability.

export default function ChatView() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [sessionId, setSessionId] = useState<string | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, busy]);

  const send = async () => {
    const text = input.trim();
    if (!text || busy) return;
    setInput("");
    setMessages((m) => [...m, { role: "user", text, at: Date.now() }]);
    setBusy(true);
    try {
      const r = await sendChat(messages, text, sessionId);
      setSessionId(r.sessionId);
      setMessages((m) => [...m, { role: "assistant", text: r.reply, at: Date.now() }]);
    } catch (e) {
      setMessages((m) => [...m, { role: "assistant", text: `❌ ${(e as Error).message}`, at: Date.now() }]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel
      title="Chat"
      className="flex h-[70vh] flex-col"
      right={
        sessionId ? (
          <span className="font-mono text-[11px] text-forge-muted">session {sessionId.slice(0, 8)}…</span>
        ) : undefined
      }
    >
      <div className="flex h-full flex-col">
        <div className="flex-1 space-y-3 overflow-y-auto pr-1">
          {messages.length === 0 && (
            <p className="py-10 text-center text-sm text-forge-muted">
              Send a message to start a turn. Try “set an alarm” or “search the web”.
            </p>
          )}
          {messages.map((m, i) => (
            <MessageBubble key={i} msg={m} />
          ))}
          {busy && <p className="text-xs text-forge-muted">Agent is thinking…</p>}
          <div ref={bottomRef} />
        </div>
        <div className="mt-3 flex gap-2 border-t border-forge-border pt-3">
          <input
            className={inputCls}
            placeholder="Message the agent…"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && send()}
          />
          <Button variant="primary" onClick={send} disabled={busy || !input.trim()}>
            Send
          </Button>
        </div>
      </div>
    </Panel>
  );
}

function MessageBubble({ msg }: { msg: ChatMessage }) {
  const isUser = msg.role === "user";
  return (
    <div className={`flex ${isUser ? "justify-end" : "justify-start"}`}>
      <div
        className={`max-w-[80%] rounded-lg px-3 py-2 text-sm ${
          isUser ? "bg-forge-accent text-white" : "border border-forge-border bg-forge-bg text-forge-text"
        }`}
      >
        {isUser ? msg.text : <AssistantText text={msg.text} />}
      </div>
    </div>
  );
}

function AssistantText({ text }: { text: string }) {
  const lines = text.split("\n");
  return (
    <div className="space-y-1">
      {lines.map((ln, i) => {
        const tool = ln.match(/^⚙\s+(.+)$/);
        if (tool) {
          return (
            <div key={i}>
              <Badge tone="accent">⚙ {tool[1]}</Badge>
            </div>
          );
        }
        const err = ln.match(/^❌\s*(.*)$/);
        if (err) {
          return (
            <div key={i}>
              <Badge tone="err">❌ {err[1]}</Badge>
            </div>
          );
        }
        return ln.trim() ? (
          <p key={i} className="whitespace-pre-wrap">
            {ln}
          </p>
        ) : null;
      })}
    </div>
  );
}
