
import re

file_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Extract tool definitions from ALL_TOOLS
all_tools_match = re.search(r'private val ALL_TOOLS = listOf\((.*?)\)', content, re.DOTALL)
if not all_tools_match:
    print("ALL_TOOLS not found")
    exit()

all_tools_content = all_tools_match.group(1)
defined_tools = re.findall(r'tool\("([^"]+)"', all_tools_content)

# Extract tool cases from the big when block
# The search starts from line 318 roughly.
when_match = re.search(r'\} \?: when \(toolName\) \{(.*?)\s+else ->', content, re.DOTALL)
if not when_match:
    print("When block not found")
    exit()

when_content = when_match.group(1)
implemented_tools = re.findall(r'"([^"]+)"\s*->', when_content)

# Also check providers
providers_chain = re.search(r'val output = if \(toolName == "python_run"\) \{.*?\} else \{(.*?)\}', content, re.DOTALL)
provider_calls = []
if providers_chain:
    provider_calls = re.findall(r'(\w+ToolProvider)\.dispatch', providers_chain.group(1))

print(f"Defined tools in ALL_TOOLS: {len(defined_tools)}")
print(f"Implemented tools in when: {len(implemented_tools)}")

missing = [t for t in defined_tools if t not in implemented_tools]

print(f"Missing in when block: {len(missing)}")
for m in missing:
    print(f"  - {m}")
