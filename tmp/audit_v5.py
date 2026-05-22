
import re
import os

file_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

all_tools_start = content.find("private val ALL_TOOLS = listOf(")
all_tools_end = content.find("    )", all_tools_start) # Note: this was the culprit!
# Balance instead
all_tools_brace_start = all_tools_start + content[all_tools_start:].find("(")
balance = 0
all_tools_end = -1
for i in range(all_tools_brace_start, len(content)):
    if content[i] == '(': balance += 1
    elif content[i] == ')':
        balance -= 1
        if balance == 0:
            all_tools_end = i
            break
all_tools_content = content[all_tools_brace_start:all_tools_end]
defined_tools = re.findall(r'tool\("([^"]+)"', all_tools_content)

# Find the REAL when block
when_start = content.find("} ?: when (toolName) {")
# Balance braces
brace_start = when_start + content[when_start:].find("{")
balance = 0
when_end = -1
for i in range(brace_start, len(content)):
    if content[i] == '{': balance += 1
    elif content[i] == '}':
        balance -= 1
        if balance == 0:
            when_end = i
            break
when_content = content[brace_start:when_end]

implemented_tools = re.findall(r'"([^"]+)"\s*->', when_content)

missing = [t for t in defined_tools if t not in implemented_tools]
print(f"Found {len(defined_tools)} defined tools.")
print(f"Found {len(implemented_tools)} implemented tools.")
print(f"Missing: {len(missing)}")
for m in missing:
    print(f"  - {m}")
