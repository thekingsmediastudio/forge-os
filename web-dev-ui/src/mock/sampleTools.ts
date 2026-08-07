import type { ToolDefinition } from "../types";

// Sample tool definitions mirroring forge-desktop/mock_server.py. Used as the
// default mock-mode tool set and the ToolEditor reset target.
export const SAMPLE_TOOLS: ToolDefinition[] = [
  {
    type: "function",
    function: {
      name: "file_read",
      description: "Read a UTF-8 text file from the workspace sandbox.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "Workspace-relative file path" },
        },
        required: ["path"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "android_battery",
      description: "Get battery level, charging state and health.",
      parameters: { type: "object", properties: {}, required: [] },
    },
  },
  {
    type: "function",
    function: {
      name: "alarm_set",
      description: "Schedule an alarm or reminder.",
      parameters: {
        type: "object",
        properties: {
          label: { type: "string", description: "Alarm label" },
          in_seconds: { type: "number", description: "Seconds from now" },
          action: {
            type: "string",
            description: "What to do when it fires",
            enum: ["NOTIFY", "RUN_PROMPT", "RUN_TOOL"],
          },
        },
        required: ["label"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "ddg_search",
      description: "Search the web via DuckDuckGo instant answers.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "Search query" },
          limit: { type: "integer", description: "Max results" },
        },
        required: ["query"],
      },
    },
  },
];
