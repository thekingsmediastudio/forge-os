import { useEffect, useRef, useState } from "react";
import { sendChat } from "../service";
import type { ChatMessage } from "../types";
import { Badge, Button, EmptyState, inputCls, Panel } from "../components/ui";

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
      className="flex h-[72vh] flex-col"
      bodyClassName="flex min-h-0 flex-1 flex-col p-0"
      right={
        sessionId ? (
          <span className="rounded-md border border-white/[0.06] bg-forge-panel2 px-2 py-0.5 font-mono text-[11px] text-forge-muted">
            session {sessionId.slice(0, 8)}…
          </span>
        ) : undefined
      }
    >
      {/* Message stream */}
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-4">
        {messages.length === 0 && !busy && (
          <EmptyState
            title="Start a turn"
            hint="Send a message to the agent. Try “set an alarm for 7am” or “search the web for news”."
          />
        )}
        {messages.map((m, i) => (
          <MessageBubble key={i} msg={m} />
        ))}
        {busy && <TypingIndicator />}
        <div ref={bottomRef} />
      </div>

      {/* Sticky composer */}
      <div className="border-t border-white/[0.06] bg-forge-panel2/40 px-4 py-3 backdrop-blur-sm">
        <div className="flex items-end gap-2">
          <div className="relative flex-1">
            <input
              className={`${inputCls} rounded-full py-2.5 pl-4 pr-10`}
              placeholder="Message the agent…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && send()}
            />
            <kbd className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 rounded border border-white/[0.08] bg-forge-panel px-1.5 py-0.5 font-mono text-[10px] text-forge-faint">
              ⏎
            </kbd>
          </div>
          <Button variant="primary" onClick={send} disabled={busy || !input.trim()} className="rounded-full px-5 py-2.5">
            Send
          </Button>
        </div>
      </div>
    </Panel>
  );
}

function Avatar({ role }: { role: "user" | "assistant" }) {
  const isUser = role === "user";
  return (
    <div
      className={`mt-0.5 flex h-6 w-6 shrink-0 select-none items-center justify-center rounded-full text-[10px] font-bold ${
        isUser
          ? "bg-forge-panel3 text-forge-muted shadow-inner-hi"
          : "bg-accent-grad text-white shadow-glow"
      }`}
    >
      {isUser ? "you" : "F"}
    </div>
  );
}

function MessageBubble({ msg }: { msg: ChatMessage }) {
  const isUser = msg.role === "user";
  return (
    <div className={`animate-fade-up flex items-start gap-2.5 ${isUser ? "flex-row-reverse" : ""}`}>
      <Avatar role={msg.role} />
      <div
        className={`max-w-[78%] rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed ${
          isUser
            ? "rounded-tr-md bg-accent-grad text-white shadow-glow"
            : "rounded-tl-md border border-white/[0.06] bg-forge-panel2/70 text-forge-text shadow-inner-hi"
        }`}
      >
        {isUser ? msg.text : <AssistantText text={msg.text} />}
      </div>
    </div>
  );
}

function TypingIndicator() {
  return (
    <div className="animate-fade-up flex items-center gap-2.5">
      <Avatar role="assistant" />
      <div className="flex items-center gap-1.5 rounded-2xl rounded-tl-md border border-white/[0.06] bg-forge-panel2/70 px-4 py-3 shadow-inner-hi">
        <span className="h-1.5 w-1.5 animate-typing-1 rounded-full bg-forge-muted" />
        <span className="h-1.5 w-1.5 animate-typing-2 rounded-full bg-forge-muted" />
        <span className="h-1.5 w-1.5 animate-typing-3 rounded-full bg-forge-muted" />
      </div>
    </div>
  );
}

function AssistantText({ text }: { text: string }) {
  const lines = text.split("\n");
  return (
    <div className="space-y-1.5">
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
