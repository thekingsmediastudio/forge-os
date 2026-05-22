
import re
import os

file_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

all_tools_start = content.find("private val ALL_TOOLS = listOf(")
all_tools_end = content.find("    )", all_tools_start) # Rough end
all_tools_content = content[all_tools_start:all_tools_end]
defined_tools = re.findall(r'tool\("([^"]+)"', all_tools_content)

when_start = content.find("} ?: when (toolName) {")
else_start = content.find("else ->", when_start)
when_content = content[when_start:else_start]

print(f"When content length: {len(when_content)}")
print(f"Sample of when content: {when_content[:500]}")

implemented_tools = re.findall(r'"([^"]+)"\s*->', when_content)
print(f"Found {len(implemented_tools)} tools in when block.")

missing = [t for t in defined_tools if t not in implemented_tools]
print(f"Missing {len(missing)} tools.")
for m in missing:
    if m == "project_serve":
        print("FOUND project_serve in missing list!")
        # Find where it is in when_content
        idx = when_content.find(m)
        print(f"Index of project_serve in when_content: {idx}")
        if idx != -1:
            print(f"Surrounding text: {when_content[idx-20:idx+50]}")
