import json
import ast
from java import jclass

class ConfigProxy:
    """A proxy for Forge OS configuration."""
    def __init__(self, relay):
        self._relay = relay
        self._cached = None

    def _load(self):
        self._cached = json.loads(self._relay.readConfig())
        return self._cached

    def get(self, path=None):
        data = self._load()
        if not path: return data
        parts = path.split('.')
        curr = data
        for p in parts:
            if isinstance(curr, dict): curr = curr.get(p)
            else: return None
        return curr

    def set(self, path, value):
        return self._relay.writeConfig(path, json.dumps(value))

    def __getitem__(self, key):
        return self.get(key)

class Forge:
    """The Forge OS Python SDK. 
    Provides access to Forge tools, alarms, and system state.
    """
    
    def __init__(self):
        try:
            Relay = jclass("com.forge.os.domain.sandbox.ForgeRelay")
            self._relay = Relay.instance
        except:
            self._relay = None
            
        if not self._relay:
            # If running outside the real app context (e.g. testing)
            self.config = {}
        else:
            self.config = ConfigProxy(self._relay)

    def call_tool(self, name, **args):
        """Call a Forge OS tool.
        Example: forge.call_tool("file_read", path="todo.md")
        """
        if not self._relay:
            print(f"[FORGE-MOCK] Calling tool {name} with {args}")
            return {"ok": False, "error": "Not in Forge OS environment"}

        args_json = json.dumps(args)
        result = self._relay.callTool(name, args_json)
        
        # Try to parse as JSON if it looks like it
        if isinstance(result, str):
            stripped = result.strip()
            if (stripped.startswith("{") and stripped.endswith("}")) or \
               (stripped.startswith("[") and stripped.endswith("]")):
                try:
                    return json.loads(stripped)
                except:
                    pass
        return result

    def __getattr__(self, name):
        """Allow calling tools as methods: forge.os.file_read(path='...')"""
        # We don't check tool list here to avoid overhead; 
        # the relay will return error if missing.
        def wrapper(**kwargs):
            return self.call_tool(name, **kwargs)
        return wrapper

    def list_tools(self):
        """Returns a list of all available tool definitions."""
        if not self._relay: return []
        return json.loads(self._relay.getDefinitionsJson())

    @property
    def tools(self):
        """Quick list of available tool names."""
        return [t['function']['name'] for t in self.list_tools()]

    # High-level convenience methods
    def set_alarm(self, time_in_sec=None, at_millis=None, label="Alarm", ring=False):
        kwargs = {"label": label, "action": "RING" if ring else "POPUP"}
        if at_millis: kwargs["at_millis"] = at_millis
        elif time_in_sec: kwargs["in_seconds"] = time_in_sec
        return self.call_tool("alarm_set", **kwargs)

    def notify(self, title, message):
        return self.call_tool("notify_send", title=title, body=message)

# Singleton instance
os = Forge()
# Compatibility alias
system = os

if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2:
        print("Forge OS SDK CLI")
        print("Usage: python -m forge_sandbox.forge <tool_name> [key=value ...]")
        print("\nAvailable tools (sample):")
        print(", ".join(os.tools[:10]) + " ...")
        sys.exit(1)
    
    tool = sys.argv[1]
    args = {}
    for pair in sys.argv[2:]:
        if '=' in pair:
            pk, pv = pair.split('=', 1)
            # Try to parse as python literals (bool, int, etc)
            try: args[pk] = ast.literal_eval(pv)
            except: args[pk] = pv
    
    print(f"🚀 Executing {tool}...")
    res = os.call_tool(tool, **args)
    if isinstance(res, (dict, list)):
        print(json.dumps(res, indent=2))
    else:
        print(res)
