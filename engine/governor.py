#!/usr/bin/env python3
"""PocketRig adaptive governor.

Runs xmrig once and continuously decides how many of the 6 performance cores it
may use, from real signals: SoC temperature, screen state, battery, and load.
Applies the decision by live-reloading xmrig's thread count via its HTTP API
(with a config-file fallback), and publishes status.json for the UI.

The decision model is identical to what the Android foreground service will run;
only the signal sources and the "apply" call differ per platform. In Phase 2 the
same decide() drives crosvm vCPU-thread affinity instead of an xmrig reload.
"""
import glob
import json
import os
import subprocess
import sys
import time
import urllib.request

ENGINE = os.path.dirname(os.path.abspath(__file__))
BASE_CFG = os.path.join(ENGINE, "config.base.json")
RUN_CFG = os.path.join(ENGINE, "config.run.json")
STATUS = os.path.join(ENGINE, "status.json")
MODE_FILE = os.path.join(ENGINE, "mode")        # "2"|"4"|"6"|"adaptive"
SCREEN_FILE = os.path.join(ENGINE, "screen")    # "on"|"off" (test override / app-provided)
ENABLE_FILE = os.path.join(ENGINE, "enabled")   # presence => mining on
XMRIG_BIN = os.path.join(ENGINE, "xmrig")
GEN = os.path.join(ENGINE, "gen_config.py")

API = "http://127.0.0.1:8181"
TOKEN = "pocketrig"
PERF_CORES = [0, 1, 2, 3, 4, 5]     # 6 performance cores; prime cores 6,7 reserved
MAX_N = 6
TICK = 3.0

# thermal thresholds (deg C)
T_HOLD, T_BACKOFF, T_CRIT = 62.0, 68.0, 75.0


def read_temp():
    hi = None
    for z in glob.glob("/sys/class/thermal/thermal_zone*"):
        try:
            with open(os.path.join(z, "type")) as f:
                if not f.read().strip().startswith("cpu"):
                    continue
            with open(os.path.join(z, "temp")) as f:
                v = int(f.read().strip())
            c = v / 1000.0 if v > 1000 else float(v)
            hi = c if hi is None else max(hi, c)
        except (OSError, ValueError):
            continue
    return hi


def read_battery():
    base = "/sys/class/power_supply/battery"
    out = {"level": None, "charging": False, "tempC": None}
    try:
        with open(os.path.join(base, "capacity")) as f:
            out["level"] = int(f.read().strip())
    except OSError:
        pass
    try:
        with open(os.path.join(base, "status")) as f:
            s = f.read().strip().lower()
        out["charging"] = s in ("charging", "full")
    except OSError:
        pass
    try:
        with open(os.path.join(base, "temp")) as f:
            out["tempC"] = int(f.read().strip()) / 10.0
    except OSError:
        pass
    return out


def read_load():
    try:
        return os.getloadavg()[0]
    except OSError:
        return 0.0


def read_screen():
    # App provides this via SCREEN_FILE; default off (background mining scenario).
    try:
        with open(SCREEN_FILE) as f:
            return f.read().strip().lower() == "on"
    except OSError:
        return False


def read_mode():
    try:
        with open(MODE_FILE) as f:
            m = f.read().strip().lower()
            return m if m in ("2", "4", "6", "adaptive") else "adaptive"
    except OSError:
        return "adaptive"


def enabled():
    return os.path.exists(ENABLE_FILE)


def decide(mode, temp, screen, batt, current):
    """Return (target_cores, human_reason). Fast throttle down, gentle ramp up."""
    base = MAX_N if mode == "adaptive" else int(mode)

    # thermal safety cap (applies to every mode — never cook the phone)
    if temp is None:
        thermal_cap, treason = MAX_N, ""
    elif temp >= T_CRIT:
        thermal_cap, treason = 1, "chip hot — protecting"
    elif temp >= T_BACKOFF:
        thermal_cap, treason = max(1, current - 1), "warm — backing off"
    elif temp >= T_HOLD:
        thermal_cap, treason = current, "warm — holding"
    else:
        thermal_cap, treason = MAX_N, ""

    # battery safety cap
    if batt["charging"]:
        batt_cap, breason = MAX_N, ""
    elif batt["level"] is not None and batt["level"] < 15:
        batt_cap, breason = 0, "battery low — paused"
    elif batt["level"] is not None and batt["level"] < 30:
        batt_cap, breason = 2, "on battery — easing"
    else:
        batt_cap, breason = MAX_N, ""

    if mode == "adaptive":
        screen_cap = 2 if screen else MAX_N
        sreason = "screen on — quiet" if screen else "screen off — free to ramp"
        target = min(base, thermal_cap, batt_cap, screen_cap)
    else:
        sreason = mode + "-core fixed"
        target = min(base, thermal_cap, batt_cap)

    # ramp: up by 1 step, down immediately
    if target > current:
        applied = current + 1
    else:
        applied = target

    reason = breason or treason or sreason
    return max(0, min(MAX_N, applied)), reason


def gen(n, wallet=None, pool=None):
    args = [sys.executable, GEN, BASE_CFG, RUN_CFG, str(n), ",".join(map(str, PERF_CORES))]
    if wallet or pool:
        args += [wallet or "", pool or ""]
    subprocess.run(args, check=True)


def api_get(path):
    req = urllib.request.Request(API + path, headers={"Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(req, timeout=2) as r:
        return json.load(r)


def api_put_config():
    with open(RUN_CFG, "rb") as f:
        body = f.read()
    req = urllib.request.Request(API + "/2/config", data=body, method="PUT",
                                 headers={"Authorization": "Bearer " + TOKEN,
                                          "Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=3).read()


def summary():
    try:
        d = api_get("/2/summary")
        hr = d.get("hashrate", {}).get("total", [0]) or [0]
        conn = d.get("connection", {})
        res = d.get("results", {})
        return {
            "hashrate": hr[0] or 0.0,
            "shares": {"good": res.get("shares_good", 0), "total": res.get("shares_total", 0)},
            "pool": {"url": conn.get("pool", ""), "connected": bool(conn.get("uptime", 0)),
                     "ping": conn.get("ping", 0)},
            "uptime": d.get("uptime", 0),
        }
    except Exception:
        return {"hashrate": 0.0, "shares": {"good": 0, "total": 0},
                "pool": {"url": "", "connected": False, "ping": 0}, "uptime": 0}


def write_status(**kw):
    tmp = STATUS + ".tmp"
    with open(tmp, "w") as f:
        json.dump(kw, f)
    os.replace(tmp, STATUS)


def xmrig_alive(proc):
    return proc is not None and proc.poll() is None


def main():
    proc = None
    current = 0
    applied = -1
    while True:
        mode = read_mode()
        on = enabled()
        temp = read_temp()
        batt = read_battery()
        screen = read_screen()

        if on:
            target, reason = decide(mode, temp, screen, batt, current)
            current = target
            if not xmrig_alive(proc):
                gen(max(1, current))
                proc = subprocess.Popen([XMRIG_BIN, "-c", RUN_CFG],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                applied = max(1, current)
                time.sleep(TICK)
                continue
            if current != applied:
                try:
                    gen(current)
                    api_put_config()
                    applied = current
                except Exception:
                    pass
        else:
            reason = ""
            current = 0
            if xmrig_alive(proc):
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except Exception:
                    proc.kill()
            proc = None
            applied = -1

        s = summary() if (on and xmrig_alive(proc)) else \
            {"hashrate": 0.0, "shares": {"good": 0, "total": 0},
             "pool": {"url": "", "connected": False, "ping": 0}, "uptime": 0}
        write_status(
            mining=on, mode=mode, activeCores=current if on else 0, maxCores=MAX_N,
            hashrate=round(s["hashrate"], 1), shares=s["shares"], pool=s["pool"],
            tempC=round(temp, 1) if temp is not None else None,
            battery={"level": batt["level"], "charging": batt["charging"],
                     "tempC": batt["tempC"]},
            uptime=s["uptime"], reason=reason,
        )
        time.sleep(TICK)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
