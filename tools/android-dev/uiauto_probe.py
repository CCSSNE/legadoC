from __future__ import annotations

import argparse
import json
import uiautomator2 as u2

from ldplayer_guard import ALLOWED_SERIAL, assert_ldplayer_target


def main() -> None:
    parser = argparse.ArgumentParser(description="Probe the LDPlayer UI through uiautomator2")
    parser.add_argument("--serial", default="127.0.0.1:5557")
    args = parser.parse_args()
    try:
        assert_ldplayer_target(args.serial)
    except RuntimeError as error:
        parser.error(str(error))

    device = u2.connect(args.serial)
    info = device.info
    current = device.app_current()
    hierarchy = device.dump_hierarchy(compressed=True)
    print(json.dumps({
        "serial": args.serial,
        "device": {
            "productName": info.get("productName"),
            "displaySize": info.get("displaySize"),
            "sdkInt": info.get("sdkInt"),
        },
        "currentApp": current,
        "hierarchyBytes": len(hierarchy.encode("utf-8")),
        "uiautomator2": "ok",
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
