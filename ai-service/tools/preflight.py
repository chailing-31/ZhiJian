"""Read-only environment summary. No installs, full-drive scans, or model deserialization."""
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

packages = {}
for name in ('fastapi', 'uvicorn', 'Pillow', 'torch', 'torchvision', 'ultralytics'):
    try:
        packages[name] = importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        packages[name] = None
report = {'python': sys.version.split()[0], 'executable': sys.executable,
          'platform': platform.system(), 'packages': packages, 'disks': {}}
for directory in (['C:\\', 'D:\\'] if os.name == 'nt' else ['/']):
    if Path(directory).exists():
        total, used, free = shutil.disk_usage(directory)
        report['disks'][directory] = {'free_gib': round(free / (1024**3), 2)}
tool = shutil.which('nvidia-smi')
report['nvidia_gpu_query'] = 'nvidia-smi not found; this does not prove that no GPU exists.'
if tool:
    try:
        p = subprocess.run([tool, '--query-gpu=name,memory.total,driver_version', '--format=csv,noheader'],
                           capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=10)
        report['nvidia_gpu_query'] = p.stdout.strip() if p.returncode == 0 else 'nvidia-smi returned an error.'
    except (OSError, subprocess.TimeoutExpired):
        report['nvidia_gpu_query'] = 'nvidia-smi query failed or timed out.'
print(json.dumps(report, ensure_ascii=False, indent=2))
