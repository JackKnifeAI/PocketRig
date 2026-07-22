#!/usr/bin/env python3
"""Generate a runnable xmrig config from config.base.json with a target thread count.

Usage: gen_config.py <base.json> <out.json> <n_threads> <affinity_csv> [wallet] [pool_url]

<affinity_csv> is a comma list of core ids to draw from, e.g. "0,1,2,3,4,5".
The first <n_threads> of those cores become the rx/0 affinity list. n_threads=0
produces an empty thread list, which pauses hashing without killing the process.
"""
import json
import sys


def main():
    base_path, out_path, n_str, aff_csv = sys.argv[1:5]
    wallet = sys.argv[5] if len(sys.argv) > 5 and sys.argv[5] else None
    pool = sys.argv[6] if len(sys.argv) > 6 and sys.argv[6] else None

    n = max(0, int(n_str))
    cores = [int(c) for c in aff_csv.split(",") if c.strip() != ""]

    with open(base_path) as fh:
        cfg = json.load(fh)

    # Affinity list: cycle through the allowed cores if n exceeds the pool.
    if cores:
        aff = [cores[i % len(cores)] for i in range(n)]
    else:
        aff = [-1] * n
    cfg.setdefault("cpu", {})["rx/0"] = aff

    if wallet or pool:
        pools = cfg.get("pools") or [{}]
        if wallet:
            pools[0]["user"] = wallet
        if pool:
            pools[0]["url"] = pool
        cfg["pools"] = pools

    tmp = out_path + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(cfg, fh, indent=2)
    import os
    os.replace(tmp, out_path)


if __name__ == "__main__":
    main()
