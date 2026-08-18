from __future__ import annotations

import subprocess
from pathlib import Path

ALLOWED_SERIAL = "127.0.0.1:5557"
LDCONSOLE = Path(r"F:\leidian\LDPlayer14\ldconsole.exe")


def assert_ldplayer_target(serial: str) -> None:
    if serial != ALLOWED_SERIAL:
        raise RuntimeError(f"refusing non-LDPlayer serial: {serial}")
    if not LDCONSOLE.is_file():
        raise RuntimeError(f"LDPlayer console is missing: {LDCONSOLE}")
    result = subprocess.run(
        [str(LDCONSOLE), "list2"],
        check=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    )
    instance = next((line for line in result.stdout.splitlines() if line.startswith("0,")), "")
    fields = instance.split(",")
    if len(fields) < 5 or fields[4] != "1":
        raise RuntimeError("LDPlayer instance 0 is not reported as running")
