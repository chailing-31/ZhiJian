"""Runtime paths are operator-owned; never read database credentials."""
from dataclasses import dataclass
import os
from pathlib import Path

@dataclass(frozen=True)
class Settings:
    storage_root: Path
    manifest_path: Path | None = None
    device: str = 'cpu'
    max_upload_bytes: int = 10 * 1024 * 1024
    max_request_bytes: int = 12 * 1024 * 1024
    max_pixels: int = 24_000_000

    @classmethod
    def from_env(cls):
        # The CMD launcher sets an absolute path on D:. Other environments must set it.
        storage = os.environ.get('AI_STORAGE_ROOT', '')
        if not storage:
            raise ValueError('Set AI_STORAGE_ROOT to an absolute, private data directory.')
        root = Path(storage).expanduser()
        if not root.is_absolute():
            raise ValueError('AI_STORAGE_ROOT must be an absolute path.')
        manifest = os.environ.get('AI_MODEL_MANIFEST', '').strip()
        return cls(storage_root=root.resolve(),
                   manifest_path=Path(manifest).expanduser().resolve() if manifest else None,
                   device=os.environ.get('AI_DEVICE', 'cpu'))
