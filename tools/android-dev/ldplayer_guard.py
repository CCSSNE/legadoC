from __future__ import annotations

import json
import subprocess
from pathlib import Path


def load_target_config() -> tuple[str, Path, int]:
    config_path = Path(__file__).with_name("target.json")
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        serial = str(config["serial"])
        ldplayer_home = Path(str(config["ldPlayerHome"]))
        instance_index = int(config["instanceIndex"])
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise RuntimeError(f"invalid Android-dev target config: {config_path}: {error}") from error
    if not serial.startswith("127.0.0.1:"):
        raise RuntimeError(f"refusing non-loopback Android target: {serial}")
    return serial, ldplayer_home / "ldconsole.exe", instance_index


ALLOWED_SERIAL, LDCONSOLE, INSTANCE_INDEX = load_target_config()
ADB = LDCONSOLE.with_name("adb.exe")


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
    instance = next(
        (line for line in result.stdout.splitlines() if line.startswith(f"{INSTANCE_INDEX},")),
        "",
    )
    fields = instance.split(",")
    if len(fields) < 5 or fields[4] != "1":
        raise RuntimeError(f"LDPlayer instance {INSTANCE_INDEX} is not reported as running")
    if not ADB.is_file():
        raise RuntimeError(f"LDPlayer adb is missing: {ADB}")
    transport = subprocess.run(
        [str(ADB), "-s", serial, "shell", "getprop", "ro.boot.serialno"],
        check=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    ).stdout.strip()
    instance_boot_serial = subprocess.run(
        [
            str(LDCONSOLE),
            "adb",
            "--index",
            str(INSTANCE_INDEX),
            "--command",
            "shell getprop ro.boot.serialno",
        ],
        check=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    ).stdout.strip()
    if not transport or not instance_boot_serial:
        raise RuntimeError("unable to read the LDPlayer boot serial for target validation")
    if transport != instance_boot_serial:
        raise RuntimeError(
            f"ADB target {serial} does not match LDPlayer instance {INSTANCE_INDEX}"
        )
