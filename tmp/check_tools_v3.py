
import re
import os

file_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Find the big when block
when_start = content.find("} ?: when (toolName) {")
if when_start == -1:
    print("When block not found")
    exit()

# Find the closing brace of the when block.
# We need to balance braces starting from when_start + content[when_start:].find("{")
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

if when_end == -1:
    print("Could not find end of when block")
    exit()

when_content = content[brace_start:when_end]

# Find all tool names in cases
implemented_tools = re.findall(r'"([^"]+)"\s*->', when_content)
print(f"Implemented tools in when: {len(implemented_tools)}")

# Get all defined tools
all_tools_start = content.find("private val ALL_TOOLS = listOf(")
# Balance again for ALL_TOOLS
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
print(f"Defined tools in ALL_TOOLS: {len(defined_tools)}")

missing = [t for t in defined_tools if t not in implemented_tools]
print(f"Missing in when block: {len(missing)}")
for m in missing:
    print(f"  - {m}")
