"""File operations helper for Forge sandbox."""

import os


def read_file(path):
    """Read text file."""
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        return f.read()


def write_file(path, content):
    """Write text file, creating parent dirs."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    return len(content)


def list_dir(path="."):
    """List directory contents."""
    items = []
    for item in sorted(os.listdir(path)):
        full = os.path.join(path, item)
        items.append({
            "name": item,
            "is_dir": os.path.isdir(full),
            "size": os.path.getsize(full) if os.path.isfile(full) else 0
        })
    return items


def get_workspace_size(path):
    """Calculate total size of directory tree."""
    total = 0
    for dirpath, dirnames, filenames in os.walk(path):
        for f in filenames:
            fp = os.path.join(dirpath, f)
            total += os.path.getsize(fp)
    return total
