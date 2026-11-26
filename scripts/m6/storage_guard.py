"""Read-only disk guard shared by the sequential M6 import runner."""

from pathlib import Path
import shutil

FLOOR = 10_000_000_000


def require_free_space():
    drives = {drive: shutil.disk_usage(drive).free for drive in ('C:/', 'D:/')}
    if min(drives.values()) < FLOOR:
        raise RuntimeError(
            f'Storage guard: require at least {FLOOR} free bytes on both C: and D:; '
            f'available {drives}. No next case may start.')
    return drives


def directory_bytes(directory):
    return sum(path.stat().st_size for path in Path(directory).rglob('*') if path.is_file())
