
import os
import re

def find_tool(tool_name):
    for root, dirs, files in os.walk(r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain"):
        for file in files:
            if file.endswith(".kt"):
                path = os.path.join(root, file)
                with open(path, 'r', encoding='utf-8') as f:
                    content = f.read()
                    if f'"{tool_name}"' in content:
                        print(f"Found '{tool_name}' in {path}")
                        if "tool(" in content: print(f"  - Appears to be a DEFINITION")
                        if "->" in content: print(f"  - Appears to be an IMPLEMENTATION (dispatch)")

find_tool("project_create")
find_tool("project_list")
