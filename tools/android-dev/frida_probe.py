from __future__ import annotations

import argparse
import json
import re
import threading
import time
from pathlib import Path

import adbutils
import frida
import frida_tools

from ldplayer_guard import ALLOWED_SERIAL, assert_ldplayer_target


def main() -> None:
    parser = argparse.ArgumentParser(description="Probe Frida through the explicit LDPlayer transport")
    parser.add_argument("--serial", default="127.0.0.1:5557")
    parser.add_argument("--port", type=int, default=27042)
    parser.add_argument("--package", default="io.legado.app.c")
    parser.add_argument("--class-name", default="")
    parser.add_argument("--method-name", default="")
    parser.add_argument("--activity-class", default="")
    parser.add_argument("--string-extra", action="append", default=[])
    parser.add_argument("--invoke-once", action="store_true")
    parser.add_argument("--deoptimize", action="store_true")
    parser.add_argument("--log-arguments", action="store_true")
    parser.add_argument("--duration", type=int, default=5)
    args = parser.parse_args()
    try:
        assert_ldplayer_target(args.serial)
    except RuntimeError as error:
        parser.error(str(error))
    if not 1024 <= args.port <= 65535:
        parser.error("--port must be between 1024 and 65535")
    if re.fullmatch(r"[A-Za-z0-9_.]+", args.package) is None:
        parser.error("--package contains unsupported characters")
    if not 1 <= args.duration <= 30:
        parser.error("duration must be between 1 and 30 seconds")
    if args.method_name and not args.class_name:
        parser.error("--method-name requires --class-name")
    if args.invoke_once and not args.method_name:
        parser.error("--invoke-once requires --method-name")
    if args.activity_class and not args.activity_class.startswith("io.legado.app."):
        parser.error("--activity-class must be an app-owned component")
    extras: dict[str, str] = {}
    for raw_extra in args.string_extra:
        key, separator, value = raw_extra.partition("=")
        if not separator or re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", key) is None:
            parser.error("--string-extra must use key=value with an identifier key")
        extras[key] = value

    adb_device = adbutils.adb.device(args.serial)
    adb_device.forward(f"tcp:{args.port}", "tcp:27042")
    remote = frida.get_device_manager().add_remote_device(f"127.0.0.1:{args.port}")
    processes = remote.enumerate_processes()
    pid_text = adb_device.shell(f"pidof {args.package}").strip()
    target_pid = int(pid_text.split()[0]) if pid_text else None
    process = next((item for item in processes if item.pid == target_pid), None)
    summary = {
        "serial": args.serial,
        "fridaDevice": {"id": remote.id, "name": remote.name, "type": remote.type},
        "processCount": len(processes),
        "sampleProcesses": [{"pid": p.pid, "name": p.name} for p in processes[:8]],
        "frida": frida.__version__,
        "target": None if process is None else {"pid": process.pid, "name": process.name},
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if process is None:
        raise RuntimeError(f"target process is not running: {args.package}")

    config = json.dumps(
        {
            "className": args.class_name,
            "methodName": args.method_name,
            "invokeOnce": args.invoke_once,
            "deoptimize": args.deoptimize,
            "logArguments": args.log_arguments,
            "activityClass": args.activity_class,
            "activityExtras": extras,
        }
    )
    source = f"""
const config = {config};
Java.perform(function () {{
    const ActivityThread = Java.use('android.app.ActivityThread');
    const application = ActivityThread.currentApplication();
    send({{
        type: 'runtime',
        applicationClass: application ? application.getClass().getName().toString() : null,
        packageName: application ? application.getPackageName().toString() : null
    }});
    if (config.activityClass) {{
        if (application === null) {{
            send({{type: 'error', message: 'application is unavailable for activity launch'}});
            return;
        }}
        const Intent = Java.use('android.content.Intent');
        const intent = Intent.$new();
        intent.setClassName(application.getPackageName(), config.activityClass);
        intent.addFlags(0x10000000);
        Object.keys(config.activityExtras).forEach(function (key) {{
            intent.putExtra(key, config.activityExtras[key]);
        }});
        application.getApplicationContext().startActivity(intent);
        send({{type: 'activity-started', component: config.activityClass}});
    }}
    if (!config.className) return;

    let matchCount = 0;
    let firstMatch = null;
    const samples = [];
    Java.choose(config.className, {{
        onMatch: function (instance) {{
            matchCount += 1;
            if (firstMatch === null) firstMatch = Java.retain(instance);
            if (samples.length < 3) {{
                let description;
                try {{ description = instance.toString(); }}
                catch (error) {{ description = '<toString failed>'; }}
                samples.push(description);
            }}
        }},
        onComplete: function () {{
            send({{type: 'instances', className: config.className, count: matchCount, samples: samples}});
        }}
    }});

    if (!config.methodName) return;
    if (config.deoptimize) {{
        Java.deoptimizeEverything();
        send({{type: 'deoptimized', scope: 'java-runtime'}});
    }}
    const Target = Java.use(config.className);
    const method = Target[config.methodName];
    if (!method) {{
        send({{type: 'error', message: 'method not found', className: config.className, methodName: config.methodName}});
        return;
    }}
    const Log = Java.use('android.util.Log');
    const Exception = Java.use('java.lang.Exception');
    function describeArgument(value) {{
        if (value === null || value === undefined) return null;
        const type = typeof value;
        if (type !== 'object') return String(value);
        try {{
            const className = value.getClass().getName().toString();
            if (className === 'android.graphics.Bitmap') {{
                return {{
                    className: className,
                    width: value.getWidth(),
                    height: value.getHeight(),
                    recycled: value.isRecycled()
                }};
            }}
            if (value.getWidth && value.getHeight) {{
                return {{ className: className, width: value.getWidth(), height: value.getHeight() }};
            }}
            const text = value.toString();
            return {{ className: className, text: text.length > 240 ? text.substring(0, 240) : text }};
        }} catch (error) {{
            return {{ className: '<unavailable>', text: String(value) }};
        }}
    }}
    method.overloads.forEach(function (overload, index) {{
        overload.implementation = function () {{
            const event = {{
                type: 'call',
                className: config.className,
                methodName: config.methodName,
                overload: index,
                thread: Java.use('java.lang.Thread').currentThread().getName().toString(),
                stack: Log.getStackTraceString(Exception.$new()).toString()
            }};
            if (config.logArguments) {{
                event.arguments = Array.prototype.map.call(arguments, describeArgument);
            }}
            send(event);
            return overload.apply(this, arguments);
        }};
    }});
    send({{type: 'trace-ready', className: config.className, methodName: config.methodName, overloads: method.overloads.length}});
    if (config.invokeOnce) {{
        if (firstMatch === null) {{
            send({{type: 'error', message: 'cannot invoke without a live instance', className: config.className}});
            return;
        }}
        const zeroArg = method.overloads.find(function (overload) {{
            return overload.argumentTypes.length === 0;
        }});
        if (!zeroArg) {{
            send({{type: 'error', message: 'invoke-once requires a zero-argument overload', methodName: config.methodName}});
            return;
        }}
        const result = zeroArg.call(firstMatch);
        send({{
            type: 'invoke-result',
            className: config.className,
            methodName: config.methodName,
            result: result === null || result === undefined ? null : result.toString()
        }});
    }}
}});
"""

    bridge_path = Path(frida_tools.__file__).parent / "bridges" / "java.js"
    if not bridge_path.is_file():
        raise RuntimeError(f"Frida Java bridge is missing: {bridge_path}")
    source = (
        bridge_path.read_text(encoding="utf-8")
        + "\nconst Java = bridge;\n"
        + source
    )

    required_events = {"runtime"}
    if args.activity_class:
        required_events.add("activity-started")
    if args.class_name:
        required_events.add("instances")
    if args.method_name:
        required_events.add("trace-ready")
    if args.deoptimize:
        required_events.add("deoptimized")
    if args.invoke_once:
        required_events.update({"call", "invoke-result"})
    received_events: set[str] = set()
    probe_ready = threading.Event()
    probe_errors: list[dict] = []

    session = remote.attach(process.pid)
    script: frida.core.Script | None = None

    def on_message(message: dict, data: bytes | None) -> None:
        payload = message.get("payload")
        printable = payload if payload is not None else message
        print(json.dumps(printable, ensure_ascii=False))
        if message.get("type") == "error" or (
            isinstance(payload, dict) and payload.get("type") == "error"
        ):
            probe_errors.append(message)
            probe_ready.set()
            return
        if isinstance(payload, dict):
            event_type = payload.get("type")
            if isinstance(event_type, str):
                received_events.add(event_type)
                if required_events.issubset(received_events):
                    probe_ready.set()

    try:
        script = session.create_script(source)
        script.on("message", on_message)
        script.load()
        if not probe_ready.wait(timeout=10):
            missing = sorted(required_events - received_events)
            raise RuntimeError(f"Frida probe initialization timed out; missing events: {missing}")
        if probe_errors:
            raise RuntimeError(f"Frida agent error: {json.dumps(probe_errors[0], ensure_ascii=False)}")
        print(
            json.dumps(
                {"type": "probe-ready", "events": sorted(received_events)},
                ensure_ascii=False,
            )
        )
        time.sleep(args.duration)
        if probe_errors:
            raise RuntimeError(f"Frida agent error: {json.dumps(probe_errors[0], ensure_ascii=False)}")
    finally:
        if script is not None:
            try:
                script.unload()
            except frida.InvalidOperationError:
                pass
        try:
            session.detach()
        except frida.InvalidOperationError:
            pass


if __name__ == "__main__":
    main()
