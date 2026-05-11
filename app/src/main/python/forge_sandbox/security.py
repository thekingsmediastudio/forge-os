import ast


BLOCKED_IMPORTS = {
    'socket', 'subprocess', 'urllib.request', 'http.client',
    'ftplib', 'smtplib', 'telnetlib', 'ssl', 'ctypes', 'mmap',
    'multiprocessing', 'concurrent.futures', 'asyncio'
}

# importlib.metadata is safe (read-only, no network/subprocess)
# but importlib.import_module and other dynamic loaders are blocked
ALLOWED_IMPORTLIB_SUBMODULES = {'metadata'}


def check_imports(code):
    """Parse AST and check for blocked imports or dynamic import attempts."""
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return True, "Syntax error in code"

    for node in ast.walk(tree):
        # 1. Standard imports
        if isinstance(node, ast.Import):
            for alias in node.names:
                root = alias.name.split('.')[0]
                if root in BLOCKED_IMPORTS:
                    return False, f"Blocked import: {alias.name}"
                # Allow importlib.metadata only
                if root == 'importlib':
                    submodule = alias.name.split('.')[1] if '.' in alias.name else ''
                    if submodule not in ALLOWED_IMPORTLIB_SUBMODULES:
                        return False, f"Blocked import: {alias.name} (only importlib.metadata is allowed)"
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                root = node.module.split('.')[0]
                if root in BLOCKED_IMPORTS:
                    return False, f"Blocked import: {node.module}"
                # Allow importlib.metadata only
                if root == 'importlib':
                    submodule = node.module.split('.')[1] if '.' in node.module else ''
                    if submodule not in ALLOWED_IMPORTLIB_SUBMODULES:
                        return False, f"Blocked import: {node.module} (only importlib.metadata is allowed)"

        # 2. Dynamic imports via __import__ or importlib
        elif isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name):
                if node.func.id == "__import__":
                    return False, "Blocked: use of __import__ is prohibited"
            elif isinstance(node.func, ast.Attribute):
                if node.func.attr == "__import__":
                    return False, "Blocked: use of .__import__ is prohibited"
                # Check for importlib.import_module etc (but allow importlib.metadata)
                if isinstance(node.func.value, ast.Name) and node.func.value.id == "importlib":
                    if node.func.attr not in ('metadata',):
                        return False, f"Blocked: use of importlib.{node.func.attr} is prohibited"

    return True, "OK"
