
import re
import os

tr_path = r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt"

with open(tr_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Get DEFINED tools.
# Look for lines starting with 'tool("'
defined = re.findall(r'tool\("([^"]+)"', content)
print(f"Found {len(defined)} tool() calls in ToolRegistry.kt")

# 2. Get IMPLEMENTED cases.
# Look for lines starting with '"tool_name" ->'
implemented = re.findall(r'"([^"]+)"\s*->', content)
print(f"Found {len(implemented)} dispatch cases in ToolRegistry.kt")

# 3. Check what's in defined but not implemented.
missing = [t for t in defined if t not in implemented]
print(f"Defined but not IMPLEMENTED in ToolRegistry.kt: {len(missing)}")

# 4. Check providers
providers = re.findall(r'private val (\w+ToolProvider)', content)
print(f"Found {len(providers)} providers in constructor.")

# For each missing tool, check if it's in a provider.
# (This is manual or we can search all files)
def check_in_providers(tool_name):
    for root, dirs, files in os.walk(r"c:\Users\hp\Downloads\forge-complete.tar\new\forge-os\forge-os\app\src\main\java\com\forge\os\domain"):
        for file in files:
            if file.endswith("Provider.kt"):
                with open(os.path.join(root, file), 'r', encoding='utf-8') as f:
                    c = f.read()
                    if f'"{tool_name}"' in c and "->" in c:
                        return file
    return None

real_missing = []
for m in missing:
    provider = check_in_providers(m)
    if provider:
        print(f"  - {m:20} -> Handled by {provider}")
    else:
        print(f"  - {m:20} -> REALLY MISSING!")
        real_missing.append(m)

print(f"\nTOTAL REALLY MISSING: {len(real_missing)}")
