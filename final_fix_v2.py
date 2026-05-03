import io
import os

# 1. Fix AiApiManager.kt
path1 = r'c:\Users\user\Downloads\forge-os-master (2)\forge-os-master\app\src\main\java\com\forge\os\data\api\AiApiManager.kt'
with io.open(path1, 'r', encoding='utf-8') as f:
    lines1 = f.readlines()

if 'suspend fun chat(' in lines1[95] and 'mode: com.forge.os.domain.companion.Mode = com.forge.os.domain.companion.Mode.AGENT,' in lines1[100]:
    print("Deleting redundant chat header in AiApiManager.kt")
    del lines1[95:101]

with io.open(path1, 'w', encoding='utf-8', newline='\n') as f:
    f.writelines(lines1)

# 2. Fix ToolRegistry.kt
path2 = r'c:\Users\user\Downloads\forge-os-master (2)\forge-os-master\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt'
with io.open(path2, 'r', encoding='utf-8') as f:
    lines2 = f.readlines()

# Line 3230 is index 3229
if lines2[3229].strip() == '),':
    print("Removing trailing comma in ToolRegistry.kt line 3230")
    lines2[3229] = lines2[3229].replace('),', ')')

with io.open(path2, 'w', encoding='utf-8', newline='\n') as f:
    f.writelines(lines2)
