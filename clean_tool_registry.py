import io
import os

path = r'c:\Users\user\Downloads\forge-os-master (2)\forge-os-master\app\src\main\java\com\forge\os\domain\agent\ToolRegistry.kt'

with io.open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Clean up multiple commas and ensure emptyList() has its parens
content = content.replace('emptyList())', 'emptyList()),') # Fix missing comma
content = content.replace('emptyList()),,,', 'emptyList()),')
content = content.replace('emptyList()),,', 'emptyList()),')
content = content.replace('emptyList()),', 'emptyList()),') # Ensure it has ()

# Fix the case where emptyList was missing its own ()
content = content.replace('emptyList),', 'emptyList()),')
content = content.replace('emptyList))', 'emptyList()),')

# Remove duplicate commas for listOf too
content = content.replace(')),,', ')),')
content = content.replace(')),,,', ')),')

# Final safety check: ensure we don't have emptyList()()
content = content.replace('emptyList()()', 'emptyList()')

with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
