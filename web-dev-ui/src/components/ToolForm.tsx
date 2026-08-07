import { useMemo, useState } from "react";
import type { ToolDefinition } from "../types";
import { Button, Field, inputCls } from "./ui";

// Schema-driven argument form for a ToolDefinition. Supports string, number,
// integer, boolean and enum parameters with type coercion and required-field
// validation, plus a raw-JSON override.

export default function ToolForm(props: {
  tool: ToolDefinition;
  onRun: (args: Record<string, unknown>) => void;
  running: boolean;
}) {
  const { tool } = props;
  const params = tool.function.parameters;
  const propsMap = params.properties ?? {};
  const required = useMemo(() => new Set(params.required ?? []), [params.required]);
  const names = Object.keys(propsMap);

  const [values, setValues] = useState<Record<string, string>>({});
  const [rawMode, setRawMode] = useState(false);
  const [raw, setRaw] = useState("{}");
  const [error, setError] = useState<string | null>(null);

  const set = (k: string, v: string) => setValues((s) => ({ ...s, [k]: v }));

  const buildArgs = (): Record<string, unknown> | null => {
    setError(null);
    if (rawMode) {
      try {
        const p = JSON.parse(raw);
        if (p === null || typeof p !== "object" || Array.isArray(p)) {
          setError("Raw args must be a JSON object.");
          return null;
        }
        return p as Record<string, unknown>;
      } catch (e) {
        setError(`Invalid JSON: ${(e as Error).message}`);
        return null;
      }
    }
    const args: Record<string, unknown> = {};
    for (const name of names) {
      const spec = propsMap[name];
      const rawVal = (values[name] ?? "").trim();
      if (rawVal === "") {
        if (required.has(name)) {
          setError(`Missing required field: ${name}`);
          return null;
        }
        continue;
      }
      const t = (spec.type ?? "string").toLowerCase();
      if (t === "number" || t === "integer") {
        const n = t === "integer" ? parseInt(rawVal, 10) : Number(rawVal);
        if (Number.isNaN(n)) {
          setError(`Field "${name}" must be a ${t}.`);
          return null;
        }
        args[name] = n;
      } else if (t === "boolean") {
        if (!/^(true|false)$/i.test(rawVal)) {
          setError(`Field "${name}" must be true or false.`);
          return null;
        }
        args[name] = rawVal.toLowerCase() === "true";
      } else {
        args[name] = rawVal;
      }
    }
    return args;
  };

  const submit = () => {
    const args = buildArgs();
    if (args) props.onRun(args);
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-forge-muted">Arguments</span>
        <button
          onClick={() => setRawMode((v) => !v)}
          className="text-xs text-forge-accent hover:underline"
        >
          {rawMode ? "Use form" : "Raw JSON"}
        </button>
      </div>

      {rawMode ? (
        <textarea
          className={`${inputCls} h-36 font-mono`}
          value={raw}
          onChange={(e) => setRaw(e.target.value)}
          spellCheck={false}
        />
      ) : names.length === 0 ? (
        <p className="text-xs text-forge-muted">This tool takes no arguments.</p>
      ) : (
        <div className="space-y-3">
          {names.map((name) => {
            const spec = propsMap[name];
            const t = (spec.type ?? "string").toLowerCase();
            return (
              <Field key={name} label={name} required={required.has(name)} hint={t}>
                {spec.enum && spec.enum.length > 0 ? (
                  <select className={inputCls} value={values[name] ?? ""} onChange={(e) => set(name, e.target.value)}>
                    <option value="">— select —</option>
                    {spec.enum.map((opt) => (
                      <option key={opt} value={opt}>
                        {opt}
                      </option>
                    ))}
                  </select>
                ) : t === "boolean" ? (
                  <select className={inputCls} value={values[name] ?? ""} onChange={(e) => set(name, e.target.value)}>
                    <option value="">— select —</option>
                    <option value="true">true</option>
                    <option value="false">false</option>
                  </select>
                ) : (
                  <input
                    className={inputCls}
                    type={t === "number" || t === "integer" ? "number" : "text"}
                    value={values[name] ?? ""}
                    onChange={(e) => set(name, e.target.value)}
                    placeholder={spec.description}
                  />
                )}
                {spec.description && !(t === "number" || t === "integer") && (
                  <span className="mt-0.5 block text-[11px] text-forge-muted/70">{spec.description}</span>
                )}
              </Field>
            );
          })}
        </div>
      )}

      {error && <p className="text-xs text-red-400">{error}</p>}

      <Button variant="primary" onClick={submit} disabled={props.running} className="w-full">
        {props.running ? "Running…" : `Run ${tool.function.name}`}
      </Button>
    </div>
  );
}
