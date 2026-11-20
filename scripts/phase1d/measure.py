#!/usr/bin/env python3
"""Phone-only measurement orchestrator: never downloads or upgrades dependencies."""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import shutil
import sys
import time

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as src:
        for part in iter(lambda: src.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()

def require_pinned(asset):
    path = pathlib.Path(asset["path"]).expanduser()
    expected = asset["sha256"].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or sha(path) != expected:
        raise ValueError("Missing file or SHA-256 mismatch: " + str(path))
    return str(path)

def sample_device():
    def read(path):
        try:
            return pathlib.Path(path).read_text().strip()
        except (OSError, PermissionError):
            return None
    battery = "/sys/class/power_supply/battery/"
    thermal = {}
    for zone in pathlib.Path("/sys/class/thermal").glob("thermal_zone*/temp"):
        thermal[zone.parent.name] = read(zone)
    api = None
    if shutil.which("termux-battery-status"):
        try:
            api = json.loads(subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=2).stdout)
        except (OSError, ValueError, subprocess.TimeoutExpired): pass
    return {"epoch_s": time.time(), "battery_percent": (api or {}).get("percentage", read(battery + "capacity")),
            "battery_temperature_raw": read(battery + "temp"),
            "battery_temperature_c": (api or {}).get("temperature"),
            "battery_plugged": (api or {}).get("plugged"),
            "battery_api_status": (api or {}).get("status"),
            "charging_status": read(battery + "status"),
            "power_mode": None,
            "thermal_zone_raw": thermal,
            "background_apps": None, "screen_state": None,
            "background_apps_note": "Record manually; unavailable from portable Termux",
            "thermal_note": "Best effort; raw sysfs temperatures may be unavailable"}

def run_command(name, cmd, output, timeout=300, samples=None):
    stdout = output / (name + ".stdout.log")
    stderr = output / (name + ".stderr.log")
    start = time.monotonic()
    peak_kib = None
    last_sensor = -5.0
    timed_out = False
    with stdout.open("w") as out, stderr.open("w") as err:
        proc = subprocess.Popen(cmd, stdout=out, stderr=err)
        while proc.poll() is None:
            elapsed = time.monotonic() - start
            if samples is not None and elapsed - last_sensor >= 5.0:
                samples.append({"stage":name, **sample_device()})
                last_sensor = elapsed
            try:
                status = pathlib.Path(f"/proc/{proc.pid}/status").read_text()
                match = re.search(r"^VmHWM:\s+(\d+) kB", status, re.MULTILINE)
                if match:
                    peak_kib = max(peak_kib or 0, int(match.group(1)))
            except OSError:
                pass
            if time.monotonic() - start > timeout:
                proc.kill()
                proc.wait()
                timed_out = True
                break
            time.sleep(.05)
    return {"exit_code": proc.returncode, "elapsed_seconds": round(time.monotonic()-start, 3),
            "peak_rss_kib_vmhwm": peak_kib, "timed_out": timed_out,
            "stdout_log": stdout.name, "stderr_log": stderr.name}

def parse_stt_events(text):
    events = []
    for line in text.splitlines():
        if line.startswith("RAIJIN_STT_EVENT "):
            try:
                obj = json.loads(line[len("RAIJIN_STT_EVENT "):])
                if isinstance(obj, dict): events.append(obj)
            except ValueError: pass
    starts = [e["elapsed_ms"] for e in events if e.get("kind") == "ready" and isinstance(e.get("elapsed_ms"), (int,float))]
    partials = [e["elapsed_ms"] for e in events if e.get("kind") == "partial" and isinstance(e.get("elapsed_ms"), (int,float))]
    delays = [e["latency_ms"] for e in events if e.get("kind") == "partial" and isinstance(e.get("latency_ms"), (int,float))]
    return {"events":events, "startup_ms":min(starts) if starts else None,
            "first_partial_ms":min(partials) if partials else None,
            "streaming_latency_ms":delays or None}

def main(config, dest):
    manifest = json.loads(pathlib.Path(config).read_text())
    if manifest["schema_version"] != 1: raise ValueError("Unsupported manifest version")
    bench = require_pinned(manifest["llama_bench"])
    models = [(m, require_pinned(m)) for m in manifest["models"]]
    if not models: raise ValueError("At least one model is required")
    if any(m["parameters_billion"] <= 0 or m["parameters_billion"] > 2 or not m["quantization"].upper().startswith("Q4") for m, _ in models):
        raise ValueError("Only <=2B Q4 candidates allowed")
    stt = manifest.get("stt")
    stt_bin = None
    audio = None
    if stt:
        stt_bin = require_pinned(stt)
        for asset in stt.get("assets", []): require_pinned(asset)
        audio = require_pinned({"path":stt["audio_file"], "sha256":stt["audio_sha256"]})
        if not stt.get("args"): raise ValueError("Pinned STT streaming invocation args required")
    if not all(x.get("source_revision") and "REPLACE" not in x["source_revision"] for x in [manifest["llama_bench"], *manifest["models"], *([stt] if stt else [])]):
        raise ValueError("Exact upstream source revisions required")
    if manifest.get("protocol", {}).get("threads", 0) <= 0: raise ValueError("threads must be positive")
    dest = pathlib.Path(dest).expanduser()
    dest.mkdir(parents=True, exist_ok=False)
    results = {"schema_version":1, "status":"incomplete", "device":sample_device(),
               "manifest_sha256":sha(config), "provenance":manifest,
               "models":[], "stt":None, "thermal_series":[], "warnings":[],
               "observed_state":{"ambient_c":None, "time_since_reboot_s":None,
                                 "cpu_governor":None, "network_mode":None,
                                 "power_profile":None, "background_apps":None}}
    path = dest / "results.json"
    def save(): path.write_text(json.dumps(results, indent=2) + "\n")
    save()
    try:
        for idx, (model, modelpath) in enumerate(models):
            trials = []
            for condition in ("fresh_process", "repeat_process"):
                name = f"model_{idx}_{condition}"
                trial = run_command(name,[bench,"-m",modelpath,"-p",
                    str(manifest["protocol"]["prefill_tokens"]),"-n",
                    str(manifest["protocol"]["generate_tokens"]),"-r","1",
                    "-t",str(manifest["protocol"]["threads"]),"-ngl","0","-o","json"],dest,
                    samples=results["thermal_series"])
                trial["condition"] = condition
                trial["prefill_tok_per_s"] = None
                trial["generation_tok_per_s"] = None
                trial["load_seconds"] = None
                trial.update({"backend":None,"ngl":0,"threads":manifest["protocol"]["threads"],"build_info":None})
                trial["note"] = "Raw llama-bench JSON retained; fresh process != physically cold. Isolated model load not measured."
                try:
                    data = json.loads((dest/trial["stdout_log"]).read_text())
                    if not isinstance(data,list): data=[data]
                    for row in data:
                        if not isinstance(row,dict): continue
                        for field, keys in (("backend",("backend","backend_name")),("ngl",("n_gpu_layers","ngl")),("threads",("n_threads","threads")),("build_info",("build_info","build_commit"))):
                            for key in keys:
                                if key in row: trial[field] = row[key]; break
                        speed = row.get("avg_ts") or row.get("tps")
                        if speed is not None:
                            if row.get("n_prompt",0)>0 and row.get("n_gen",0)==0: trial["prefill_tok_per_s"]=speed
                            if row.get("n_gen",0)>0 and row.get("n_prompt",0)==0: trial["generation_tok_per_s"]=speed
                except (ValueError, OSError, AttributeError, TypeError):
                    results["warnings"].append(name + ": cannot parse llama-bench token rates")
                trials.append(trial)
                save()
            results["models"].append({"model_sha256":model["sha256"],"trials":trials})
            results["warnings"].append("Model %s: declared parameter count and Q4 quantization not independently verified against GGUF metadata" % idx)
            save()
        if stt:
            results["stt"] = run_command("stt_streaming",
                [stt_bin,*[str(x).replace("{audio}",audio) for x in stt["args"]]],dest,timeout=180,
                samples=results["thermal_series"])
            results["stt"].update(parse_stt_events((dest/results["stt"]["stdout_log"]).read_text(errors="replace")))
            save()
        start = time.monotonic()
        while time.monotonic()-start < manifest["protocol"]["thermal_seconds"]:
            results["thermal_series"].append(sample_device())
            save()
            time.sleep(5)
        results["status"] = "complete_with_unmeasured_fields"
    except Exception as exc:
        results["warnings"].append(type(exc).__name__ + ": " + str(exc))
        raise
    finally:
        save()
    print(str(path))

if __name__ == "__main__":
    if len(sys.argv) != 3: raise SystemExit("Usage: measure.py PINNED_MANIFEST.json OUTPUT_DIRECTORY")
    main(sys.argv[1],sys.argv[2])
