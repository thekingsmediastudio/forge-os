"""Python code execution with output capture and security restrictions."""

import sys
import io
import os
import json
import traceback
import builtins as _builtins
from forge_sandbox.security import check_imports


def run_python(code, workspace_path, profile="default", timeout=30):
    """Execute Python code in restricted environment.

    Args:
        code: Python source code to execute
        workspace_path: Directory to run in
        profile: "default" (strict) or "plugin" (allows imports for loader)
        timeout: Max execution time (enforced by Kotlin caller)

    Returns:
        JSON string with execution results
    """
    # 1. Static Security Check
    allowed, reason = check_imports(code)
    if not allowed:
        return json.dumps({
            "success": False,
            "output": "",
            "error": f"Security Error: {reason}",
            "truncated": False
        })

    # Change to workspace
    old_cwd = os.getcwd()
    os.chdir(workspace_path)

    # Capture stdout/stderr
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = captured_output = io.StringIO()
    sys.stderr = captured_error = io.StringIO()

    try:
        # Restricted globals - limit builtins
        allowed_builtins = [
            'abs', 'all', 'any', 'ascii', 'bin', 'bool', 'bytearray',
            'bytes', 'callable', 'chr', 'classmethod', 'complex',
            'delattr', 'dict', 'dir', 'divmod', 'enumerate', 'filter',
            'float', 'format', 'frozenset', 'getattr', 'globals',
            'hasattr', 'hash', 'hex', 'id', 'input', 'int', 'isinstance',
            'issubclass', 'iter', 'len', 'list', 'locals', 'map', 'max',
            'memoryview', 'min', 'next', 'object', 'oct', 'open', 'ord',
            'pow', 'print', 'property', 'range', 'repr', 'reversed',
            'round', 'set', 'setattr', 'slice', 'sorted', 'staticmethod',
            'str', 'sum', 'super', 'tuple', 'type', 'vars', 'zip',
            'True', 'False', 'None', 'Exception',
            'ArithmeticError', 'AssertionError', 'AttributeError',
            'BlockingIOError', 'BrokenPipeError', 'BufferError',
            'BytesWarning', 'ChildProcessError', 'ConnectionAbortedError',
            'ConnectionError', 'ConnectionRefusedError', 'ConnectionResetError',
            'DeprecationWarning', 'EOFError', 'EnvironmentError',
            'FileExistsError', 'FileNotFoundError', 'FloatingPointError',
            'FutureWarning', 'GeneratorExit', 'IOError', 'ImportError',
            'ImportWarning', 'IndentationError', 'IndexError', 'InterruptedError',
            'IsADirectoryError', 'KeyError', 'KeyboardInterrupt', 'LookupError',
            'MemoryError', 'ModuleNotFoundError', 'NameError', 'NotADirectoryError',
            'NotImplementedError', 'OSError', 'OverflowError', 'PendingDeprecationWarning',
            'PermissionError', 'ProcessLookupError', 'RecursionError', 'ReferenceError',
            'ResourceWarning', 'RuntimeError', 'RuntimeWarning', 'StopAsyncIteration',
            'StopIteration', 'SyntaxError', 'SyntaxWarning', 'SystemError',
            'SystemExit', 'TabError', 'TimeoutError', 'TypeError', 'UnboundLocalError',
            'UnicodeDecodeError', 'UnicodeEncodeError', 'UnicodeError',
            'UnicodeTranslationError', 'UnicodeWarning', 'UserWarning',
            'ValueError', 'Warning', 'ZeroDivisionError'
        ]

        # Convert list to dict of builtins
        builtins_dict = {
            name: getattr(_builtins, name)
            for name in allowed_builtins
            if hasattr(_builtins, name)
        }

        # Always include __import__ so that standard `import X` statements work
        # inside exec(). The AST security check above (check_imports) already
        # blocks any dangerous modules and any direct __import__() calls, so
        # it is safe to expose the import machinery here.
        builtins_dict['__import__'] = _builtins.__import__

        def forge_pause(local_vars):
            try:
                from java import jclass
                DebuggerRelay = jclass("com.forge.os.domain.sandbox.DebuggerRelay")
                relay = DebuggerRelay.getInstance()
                if not relay:
                    print("WARNING: DebuggerRelay not available.")
                    return
                
                # Convert to simple string map
                safe_vars = {}
                for k, v in local_vars.items():
                    if k.startswith("__"): continue
                    if callable(v) or type(v).__name__ == "module": continue
                    safe_vars[k] = str(v)
                
                # Call Kotlin method (blocks python thread)
                modified_vars_map = relay.waitForUser(safe_vars)
                
                # Apply changes back to local_vars
                if modified_vars_map:
                    for k in modified_vars_map.keySet():
                        val_str = modified_vars_map.get(k)
                        try:
                            import ast
                            local_vars[k] = ast.literal_eval(val_str)
                        except:
                            local_vars[k] = val_str

            except Exception as e:
                print("Debugger Error: " + str(e))
                import traceback
                traceback.print_exc()

        builtins_dict['forge_pause'] = forge_pause

        # Restricted globals
        restricted_globals = {
            "__builtins__": builtins_dict,
            "__name__": "__main__",
        }

        # Execute
        exec(code, restricted_globals)

        output = captured_output.getvalue()
        error = captured_error.getvalue()

        return json.dumps({
            "success": True,
            "output": output,
            "error": error,
            "truncated": len(output) > 10000
        })

    except Exception as e:
        return json.dumps({
            "success": False,
            "output": captured_output.getvalue(),
            "error": f"{type(e).__name__}: {str(e)}\n{traceback.format_exc()}",
            "truncated": False
        })
    finally:
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        os.chdir(old_cwd)
