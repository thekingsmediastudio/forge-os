
import re
import os

file_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

all_tools_start = content.find("private val ALL_TOOLS = listOf(")
all_tools_brace_start = all_tools_start + content[all_tools_start:].find("(")
balance = 0
all_tools_end = -1
for i in range(all_tools_brace_start, len(content)):
    if content[i] == '(':
        balance += 1
    elif content[i] == ')':
        balance -= 1
        if balance == 0:
            all_tools_end = i
            break

all_tools_content = content[all_tools_brace_start:all_tools_end]
defined_tools = re.findall(r'tool\("([^"]+)"', all_tools_content)

if "project_create" in defined_tools:
    print("project_create is in ALL_TOOLS")
else:
    print("project_create is NOT in ALL_TOOLS")

# Check if it's in implemented tools
when_start = content.find("} ?: when (toolName) {")
brace_start = when_start + content[when_start:].find("{")
balance = 0
when_end = -1
for i in range(brace_start, len(content)):
    if content[i] == '{':
        balance += 1
    elif content[i] == '}':
        balance -= 1
        if balance == 0:
            when_end = i
            break

when_content = content[brace_start:when_end]
if "project_create" in when_content:
    print("project_create is in the 'when' block")
else:
    print("project_create is NOT in the 'when' block")
