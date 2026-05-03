import io
import os

path = r'c:\Users\user\Downloads\forge-os-master (2)\forge-os-master\app\src\main\java\com\forge\os\presentation\screens\ChatViewModel.kt'

with io.open(path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 1. Identify components
handle_slash_start = -1
for i, line in enumerate(lines):
    if 'private fun handleSlashCommand' in line:
        handle_slash_start = i
        break

# handle_slash_when_end was index 319
# handle_slash_fun_end was index 320
# approveCost was 323-329 (index 322-328)
# rejectCost was 331-336 (index 330-335)
# dangling_start was 337 (index 336)
# dangling_end was 381 (index 380)

if handle_slash_start != -1:
    # Extract approveCost and rejectCost
    approve_reject_block = lines[322:336]
    # Extract dangling branches
    dangling_block = lines[336:381]
    
    # Reconstruct handleSlashCommand
    # Remove the premature closures and the misplaced functions
    new_lines = lines[:319] # Up to before the } at 320
    new_lines.extend(dangling_block) # Append the branches
    # dangling_block already has the closing } for when and fun at the end
    
    # Now append approveCost and rejectCost after the function
    new_lines.extend(['\n'])
    new_lines.extend(approve_reject_block)
    
    # Append the rest of the file
    new_lines.extend(lines[381:])
    
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.writelines(new_lines)
    print("Fixed ChatViewModel.kt")
else:
    print("Could not find handleSlashCommand")
