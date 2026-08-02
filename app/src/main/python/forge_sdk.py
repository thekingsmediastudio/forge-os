"""
Forge OS Python SDK

A lightweight client library for interacting with the Forge OS agent
from external Python scripts running on the same device.

Usage:
    from forge_sdk import ForgeClient

    # Connect to the local Forge API server
    client = ForgeClient(token="your-auth-token")

    # List available tools
    tools = client.list_tools()
    print(tools)

    # Call a tool
    result = client.call_tool("file_read", path="notes/todo.md")
    print(result)

Requirements:
    - Forge OS app running with API server enabled (Settings → API Server)
    - Auth token from Settings → API Server

The SDK uses only Python standard library modules (no external dependencies).
"""

import json
import socket
from typing import Any, Dict, List, Optional


class ForgeClient:
    """
    Client for the Forge OS local API server.

    Attributes:
        host: Server hostname (default: 127.0.0.1)
        port: Server port (default: 8765)
        token: Bearer token for authentication
        timeout: Socket timeout in seconds (default: 30)
    """

    DEFAULT_HOST = "127.0.0.1"
    DEFAULT_PORT = 8789  # ForgeHttpServer default port
    DEFAULT_TIMEOUT = 30

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        token: str = "",
        timeout: int = DEFAULT_TIMEOUT,
    ):
        self.host = host
        self.port = port
        self.token = token
        self.timeout = timeout

    def health_check(self) -> Dict[str, Any]:
        """
        Check if the Forge API server is running.

        Returns:
            dict: {"status": "ok", ...} on success

        Raises:
            ConnectionError: If server is not reachable
        """
        return self._request("GET", "/api/status", auth=False)

    def list_tools(self) -> List[Dict[str, str]]:
        """
        List all available tools.

        Returns:
            list: [{"name": "file_read", "description": "..."}, ...]

        Raises:
            AuthenticationError: If token is invalid
            ConnectionError: If server is not reachable
        """
        response = self._request("GET", "/api/tools")
        return response.get("tools", [])

    def call_tool(self, tool_name: str, **kwargs) -> str:
        """
        Execute a Forge tool with the given arguments.

        Args:
            tool_name: Name of the tool to execute (e.g., "file_read")
            **kwargs: Tool arguments as keyword arguments

        Returns:
            str: Tool output

        Raises:
            AuthenticationError: If token is invalid
            ToolError: If tool execution fails
            ConnectionError: If server is not reachable

        Example:
            result = client.call_tool("file_read", path="notes/todo.md")
            result = client.call_tool("python_run", code="print('hello')")
        """
        payload = {
            "name": tool_name,
            "args": kwargs,
        }
        response = self._request("POST", "/api/tool", payload)

        if not response.get("ok", False):
            error = response.get("error", "Unknown error")
            raise ToolError(f"Tool '{tool_name}' failed: {error}")

        return response.get("output", "")

    def _request(
        self,
        method: str,
        path: str,
        payload: Optional[Dict] = None,
        auth: bool = True,
    ) -> Dict[str, Any]:
        """
        Send an HTTP request to the Forge API server.

        Args:
            method: HTTP method (GET, POST)
            path: Request path (/health, /tools, /tool)
            payload: JSON payload for POST requests
            auth: Whether to include Authorization header

        Returns:
            dict: Parsed JSON response

        Raises:
            AuthenticationError: If authentication fails
            ConnectionError: If server is not reachable
        """
        body = json.dumps(payload) if payload else ""

        # Build HTTP request
        lines = [
            f"{method} {path} HTTP/1.1",
            f"Host: {self.host}:{self.port}",
            "Content-Type: application/json",
            f"Content-Length: {len(body)}",
            "Connection: close",
        ]

        if auth and self.token:
            lines.append(f"Authorization: Bearer {self.token}")

        lines.append("")  # Empty line before body
        lines.append(body)

        request = "\r\n".join(lines)

        try:
            # Create socket and send request
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(self.timeout)
            sock.connect((self.host, self.port))
            sock.sendall(request.encode("utf-8"))

            # Read response
            response_data = b""
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                response_data += chunk

            sock.close()

            # Parse HTTP response
            response_str = response_data.decode("utf-8")
            header_end = response_str.find("\r\n\r\n")
            if header_end == -1:
                raise ConnectionError("Invalid HTTP response")

            headers = response_str[:header_end]
            body = response_str[header_end + 4:]

            # Check status code
            status_line = headers.split("\r\n")[0]
            status_code = int(status_line.split(" ")[1])

            if status_code == 401:
                raise AuthenticationError("Invalid or missing auth token")
            elif status_code == 404:
                raise ConnectionError(f"Endpoint not found: {path}")
            elif status_code >= 400:
                try:
                    error_data = json.loads(body)
                    raise ConnectionError(f"Server error: {error_data.get('error', body)}")
                except json.JSONDecodeError:
                    raise ConnectionError(f"Server error: {body}")

            # Parse JSON body
            return json.loads(body) if body else {}

        except socket.timeout:
            raise ConnectionError(f"Connection to {self.host}:{self.port} timed out")
        except socket.error as e:
            raise ConnectionError(f"Failed to connect to {self.host}:{self.port}: {e}")
        except json.JSONDecodeError as e:
            raise ConnectionError(f"Invalid JSON response: {e}")


class ForgeError(Exception):
    """Base exception for Forge SDK errors."""
    pass


class AuthenticationError(ForgeError):
    """Raised when authentication fails."""
    pass


class ToolError(ForgeError):
    """Raised when a tool execution fails."""
    pass


class ConnectionError(ForgeError):
    """Raised when connection to the server fails."""
    pass


# ── Convenience functions ─────────────────────────────────────────────────────

def connect(token: str, host: str = ForgeClient.DEFAULT_HOST, port: int = ForgeClient.DEFAULT_PORT) -> ForgeClient:
    """
    Create a ForgeClient with the given token.

    Args:
        token: Auth token from Settings → API Server
        host: Server hostname (default: 127.0.0.1)
        port: Server port (default: 8765)

    Returns:
        ForgeClient: Connected client instance

    Example:
        client = connect("abc123...")
        result = client.call_tool("file_list", path=".")
    """
    return ForgeClient(host=host, port=port, token=token)


def quick_call(tool_name: str, token: str, **kwargs) -> str:
    """
    One-liner to call a tool without creating a client instance.

    Args:
        tool_name: Name of the tool to execute
        token: Auth token
        **kwargs: Tool arguments

    Returns:
        str: Tool output

    Example:
        result = quick_call("file_read", token="abc123", path="notes/todo.md")
    """
    client = ForgeClient(token=token)
    return client.call_tool(tool_name, **kwargs)


# ── CLI entry point ───────────────────────────────────────────────────────────

if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Forge OS Python SDK")
        print()
        print("Usage:")
        print("  python forge_sdk.py <token>                    # Test connection")
        print("  python forge_sdk.py <token> tools              # List tools")
        print("  python forge_sdk.py <token> call <tool> [args] # Call a tool")
        print()
        print("Examples:")
        print("  python forge_sdk.py abc123 tools")
        print('  python forge_sdk.py abc123 call file_read path=notes/todo.md')
        sys.exit(0)

    token = sys.argv[1]
    client = ForgeClient(token=token)

    if len(sys.argv) == 2:
        # Test connection
        try:
            health = client.health_check()
            print(f"✅ Connected to Forge OS API server")
            print(f"   Status: {health.get('status')}")
            print(f"   Version: {health.get('version')}")
        except Exception as e:
            print(f"❌ Connection failed: {e}")
            sys.exit(1)

    elif sys.argv[2] == "tools":
        # List tools
        try:
            tools = client.list_tools()
            print(f"Available tools ({len(tools)}):")
            for tool in tools:
                print(f"  • {tool['name']}: {tool['description'][:60]}...")
        except Exception as e:
            print(f"❌ Failed to list tools: {e}")
            sys.exit(1)

    elif sys.argv[2] == "call" and len(sys.argv) >= 4:
        # Call a tool
        tool_name = sys.argv[3]
        kwargs = {}
        for arg in sys.argv[4:]:
            if "=" in arg:
                key, value = arg.split("=", 1)
                kwargs[key] = value

        try:
            result = client.call_tool(tool_name, **kwargs)
            print(result)
        except Exception as e:
            print(f"❌ Tool call failed: {e}")
            sys.exit(1)

    else:
        print("Unknown command. Use 'tools' or 'call <tool> [args]'")
        sys.exit(1)
