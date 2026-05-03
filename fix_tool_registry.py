import io
import os

path = r'c:\Users\user\Downloads\forge-os-master (2)\forge-os-master\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt'

with io.open(path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

def fix_line(idx, target, replacement):
    if idx < len(lines):
        if target in lines[idx]:
            lines[idx] = lines[idx].replace(target, replacement)
            print(f"Fixed line {idx+1}")
        else:
            print(f"Warning: Target '{target}' not found in line {idx+1}: {repr(lines[idx])}")

# Fix emptyList calls (missing parenthesis and missing comma)
for i in [2932, 2939, 2945, 2975]:
    fix_line(i, 'emptyList())', 'emptyList()),')

# Add missing commas for other tool definitions
for i in [2881, 2891, 2901, 2911, 2919, 2922, 2952, 2960, 2967, 2980, 2988, 2994, 3002, 3006, 3026, 3037, 3067, 3071, 3095, 3103, 3121, 3127, 3135, 3158, 3166, 3176, 3182, 3195, 3203, 3210, 3217]:
    line = lines[i]
    if not line.strip().endswith(','):
        lines[i] = line.rstrip() + ',\n'
        print(f"Added comma to line {i+1}: {repr(lines[i])}")
    else:
        print(f"Line {i+1} already has comma: {repr(line)}")

with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.writelines(lines)
