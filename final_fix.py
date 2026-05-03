import io
import os

path = r'c:\Users\user\Downloads\forge-os-master (2)\forge-os-master\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt'

with io.open(path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

for i in range(2483, 3230):
    line = lines[i]
    # Fix emptyList missing ()
    if 'emptyList' in line and 'emptyList()' not in line:
        line = line.replace('emptyList', 'emptyList()')
    
    # Ensure it ends with a comma if it ends with )
    stripped = line.strip()
    if stripped.endswith(')') and not stripped.endswith(','):
        line = line.rstrip() + ',\n'
    
    lines[i] = line

with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.writelines(lines)
