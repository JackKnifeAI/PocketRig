# PocketRig — design notes

## Runtime target (decided 2026-07-19)
- **Phase 1 (this session): NDK-native.** xmrig cross-compiled with the Android NDK
  (bionic libc), run as a native process spawned by the app's foreground service.
  Bare-metal on the real kernel, no proot, no VM tax. Full adaptive control, direct
  `/sys/class/thermal` + BatteryManager + screen-state access.
- **Phase 2 (later): Microdroid pVM via AVF.** Same native payload lifted into a
  protected VM (`android.system.virtualmachine` / crosvm) for hardware-boundary isolation.

## Adaptive governor — unified control model
The governor never hot-plugs CPUs. It controls a fixed set of worker threads by
**restricting which physical cores they may run on** (thread affinity) and/or a
**cpuset/CPU-quota cgroup**. This mechanism is identical in both phases:

- Phase 1: the workers are xmrig's RandomX threads (native process).
- Phase 2: boot Microdroid once with `CPU_TOPOLOGY_MATCH_HOST`, then the host-side
  governor throttles the **crosvm vCPU threads** the same way (affinity / cgroup).
  Governor stays host-side with native signals; no vsock needed for control.

So the governor is written once against "a target process's worker threads" and reused.

## Core policy
- Device: 8 cores = 6 performance @ 3.53 GHz (cpu0-5) + 2 prime @ 4.47 GHz (cpu6-7).
- Mining uses the **6 performance cores**; the **2 prime cores are reserved** for UI/system
  responsiveness. Fixed modes: 2 / 4 / 6 cores. Adaptive: 1..6.
- Inputs: SoC temp (max of cpu* thermal zones), screen on/off, battery status+level, load.
- Policy: screen ON -> back off to 1-2 cores (stay snappy). Screen OFF -> ramp toward 6,
  gated by thermal (hold ~60C, back off ~68C, emergency 1-core >75C) and battery
  (throttle when discharging & low; full when charging).
- Hysteresis + gradual +/-1 core steps to avoid oscillation.

## Optional privacy
- Route pool traffic through Orbot SOCKS5 (127.0.0.1:9050) via per-pool `socks5` in
  xmrig config. Adds latency -> a few more stale shares; off by default, user toggle.

## Signing
- Reuse the existing on-device apksigner from the prior app build (Termux). Located below.
