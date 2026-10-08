"""Keep test scratch files private to each invocation, including on Windows."""

from pathlib import Path
from tempfile import TemporaryDirectory

import pytest


@pytest.hookimpl(tryfirst=True)
def pytest_configure(config):
    # A sandbox process and a regular terminal can share the same TEMP and
    # username but have different Windows identities. pytest-of-<user> then
    # belongs to just one of them. Allocate a fresh directory for this process.
    if config.option.basetemp is not None:
        return
    scratch = TemporaryDirectory(prefix="zhijian-tests-", ignore_cleanup_errors=True)
    config.option.basetemp = str(Path(scratch.name) / "work")
    config.add_cleanup(scratch.cleanup)
