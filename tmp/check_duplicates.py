
import re
import os

tool_registry_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"

def get_tools_from_file(path):
    if not os.path.exists(path): return []
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    return re.findall(r'tool\("([^"]+)"', content)

# 1. Get ALL_TOOLS from ToolRegistry
with open(tool_registry_path, 'r', encoding='utf-8') as f:
    tr_content = f.read()

all_tools_match = re.search(r'private val ALL_TOOLS = listOf\((.*?)\n    \)', tr_content, re.DOTALL)
all_tools = re.findall(r'tool\("([^"]+)"', all_tools_match.group(1)) if all_tools_match else []

# 2. Get tools from all providers mentioned in constructor
providers = re.findall(r'private val (\w+ToolProvider):', tr_content)
print(f"Found providers: {providers}")

all_provider_tools = {}
for p in providers:
    # Try to find the file for this provider
    # Heuristic: convert camelCase to PascalCase and find in Providers folder
    p_name = p[0].upper() + p[1:]
    potential_paths = [
        f"app\\src\\main\\java\\com\\forge\\os\\domain\\agent\\providers\\{p_name}.kt",
        f"app\\src\\main\\java\\com\\forge\\os\\domain\\projects\\{p_name}.kt",
    ]
    found = False
    for path in potential_paths:
        full_path = os.path.join(r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os", path)
        if os.path.exists(full_path):
            tools = get_tools_from_file(full_path)
            all_provider_tools[p] = tools
            found = True
            break
    if not found:
        print(f"Could not find source for {p}")

# 3. Check for duplicates
all_names = []
for p, tools in all_provider_tools.items():
    for t in tools:
        if t in all_names:
            print(f"DUPLICATE TOOL NAME: {t} (in {p})")
        all_names.append(t)

for t in all_tools:
    if t in all_names:
        print(f"DUPLICATE TOOL NAME: {t} (in ALL_TOOLS)")
    all_names.append(t)

print(f"Total unique tools discovered: {len(set(all_names))}")
print(f"Total tool instances: {len(all_names)}")
