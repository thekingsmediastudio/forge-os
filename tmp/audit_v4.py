
import re
import os

tool_registry_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"
project_provider_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\projects\ProjectToolProvider.kt"

with open(tool_registry_path, 'r', encoding='utf-8') as f:
    tr_content = f.read()

with open(project_provider_path, 'r', encoding='utf-8') as f:
    pp_content = f.read()

# 1. Get all tools from ALL_TOOLS
all_tools_match = re.search(r'private val ALL_TOOLS = listOf\((.*?)\n    \)', tr_content, re.DOTALL)
all_tools = re.findall(r'tool\("([^"]+)"', all_tools_match.group(1)) if all_tools_match else []

# 2. Get all tools from ProjectToolProvider
pp_tools = re.findall(r'tool\("([^"]+)"', pp_content)

# 3. Get all tools implemented in ToolRegistry.dispatch when block
when_start = tr_content.find("} ?: when (toolName) {")
brace_start = when_start + tr_content[when_start:].find("{")
balance = 0
when_end = -1
for i in range(brace_start, len(tr_content)):
    if tr_content[i] == '{':
        balance += 1
    elif tr_content[i] == '}':
        balance -= 1
        if balance == 0:
            when_end = i
            break
when_content = tr_content[brace_start:when_end]
implemented_in_tr = re.findall(r'"([^"]+)"\s*->', when_content)

# 4. Get all tools implemented in ProjectToolProvider.dispatch
pp_implemented = re.findall(r'"([^"]+)"\s*->', pp_content)

# 5. Check dependencies
# (This is simplified, should really look at ToolRegistry.kt dispatch loop)
providers_in_chain = re.findall(r'(\w+ToolProvider)\.dispatch', tr_content)

print(f"Total tools in ALL_TOOLS: {len(all_tools)}")
print(f"Total tools in ProjectToolProvider: {len(pp_tools)}")

print("\n--- ProjectToolProvider Check ---")
for t in pp_tools:
    is_implemented = t in pp_implemented
    is_in_chain = "projectToolProvider" in tr_content # Rough check
    print(f"Tool {t:20}: Implemented={is_implemented}, InChain={is_in_chain}")

print("\n--- ALL_TOOLS Check ---")
missing_in_when = [t for t in all_tools if t not in implemented_in_tr]
print(f"Missing in ToolRegistry when block: {len(missing_in_when)}")
for m in missing_in_when:
    # Check if they are in any other provider (simplified)
    print(f"  - {m}")
