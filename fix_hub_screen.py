import io
import os

path = r'c:\Users\user\Downloads\forge-os-master (2)\forge-os-master\app\src\main\java\com\forge\os\presentation\screens\hub\HubScreen.kt'

with io.open(path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find Grid start
start_idx = -1
for i in range(70, len(lines)):
    if 'LazyVerticalGrid' in lines[i]:
        # Search forward for the opening brace
        for j in range(i, len(lines)):
            if '{' in lines[j]:
                start_idx = j
                print(f"Found Grid start brace at index {j} (line {j+1})")
                break
        if start_idx != -1: break

if start_idx != -1:
    brace_count = 1
    for j in range(start_idx + 1, len(lines)):
        brace_count += lines[j].count('{')
        brace_count -= lines[j].count('}')
        if brace_count == 0:
            print(f"Found Grid end at index {j} (line {j+1})")
            # The Grid ends at line 116 (index 115)
            # The extra braces are at 116, 117, 118
            if j+3 < len(lines) and lines[j+1].strip() == '}' and lines[j+2].strip() == '}' and lines[j+3].strip() == '}':
                print("Found premature closures at j+1, j+2, j+3. Removing them.")
                del lines[j+1:j+4]
                
                # Append them before QuickActionItem
                for k in range(j+1, len(lines)):
                    if 'private fun QuickActionItem' in lines[k]:
                        lines.insert(k, '        }\n')
                        lines.insert(k+1, '    }\n')
                        lines.insert(k+2, '}\n')
                        print(f"Inserted missing braces at index {k}")
                        break
                break
            else:
                print(f"Next lines are not extra braces: {repr(lines[j+1:j+4])}")
            break

with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.writelines(lines)
