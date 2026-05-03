import io
import os

path = r'c:\Users\user\Downloads\forge-os-master (2)\forge-os-master\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt'

with io.open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace any occurrence of 'emptyList))' with 'emptyList()),'
content = content.replace('emptyList())', 'emptyList()),')

# Remove duplicate commas
content = content.replace('),,,', '),')
content = content.replace('),,', '),')
content = content.replace(')),,', ')),')

with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
