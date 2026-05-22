
import re
import os

file_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"

print(f"Reading {file_path}...")
if not os.path.exists(file_path):
    print("File not found!")
    exit()

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Extract tool definitions from ALL_TOOLS
print("Extracting ALL_TOOLS...")
# Find the start of ALL_TOOLS
all_tools_start = content.find("private val ALL_TOOLS = listOf(")
if all_tools_start == -1:
    print("ALL_TOOLS not found")
    exit()

# Find the end of ALL_TOOLS
# We look for the closing parenthesis of listOf
balance = 0
all_tools_end = -1
for i in range(all_tools_start + len("private val ALL_TOOLS = listOf"), len(content)):
    if content[i] == '(':
        balance += 1
    elif content[i] == ')':
        if balance == 0:
            all_tools_end = i
            break
        else:
            balance -= 1

if all_tools_end == -1:
    print("Could not find end of ALL_TOOLS")
    exit()

all_tools_content = content[all_tools_start:all_tools_end]
defined_tools = re.findall(r'tool\("([^"]+)"', all_tools_content)

print(f"Defined tools: {len(defined_tools)}")

# Extract tool cases from the big when block
print("Extracting when block...")
when_start = content.find("} ?: when (toolName) {")
if when_start == -1:
    print("When block not found")
    exit()

# Find the else -> of that when block
else_start = content.find("else ->", when_start)
if else_start == -1:
    print("Else block not found")
    exit()

when_content = content[when_start:else_start]
implemented_tools = re.findall(r'"([^"]+)"\s*->', when_content)

print(f"Implemented tools in when: {len(implemented_tools)}")

missing = [t for t in defined_tools if t not in implemented_tools]

print(f"Missing in when block: {len(missing)}")
for m in missing:
    print(f"  - {m}")
