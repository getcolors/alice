#!/usr/bin/env python3
"""Verify published standalone launcher from an unrelated temporary directory."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
source = root / 'alice'
if source.read_bytes() != (root / 'skills/alice/alice').read_bytes():
    raise SystemExit('Portable launcher copies differ')
with tempfile.TemporaryDirectory(prefix='alice-launcher-') as folder:
    copied = Path(folder) / 'alice'
    shutil.copy2(source, copied)
    result = subprocess.run([str(copied), '--help'], cwd=folder, capture_output=True, text=True)
    if result.returncode or 'Private Transmission' not in result.stdout:
        raise SystemExit('Copied launcher validation failed; output suppressed')
print('Copied launcher passed from unrelated directory')
