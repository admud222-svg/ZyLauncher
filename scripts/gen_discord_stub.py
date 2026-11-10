#!/usr/bin/env python3
"""Zy Launcher: list the Discord_* symbols libgxcore.so imports (input for discord_stub.cpp).

Usage: python3 scripts/gen_discord_stub.py app/src/main/jniLibs/arm64-v8a/libgxcore.so
Prints one symbol per line; regenerate app/src/main/cpp/zy_discord_stub/discord_stub.cpp when
libgxcore.so changes upstream (every name needs a `ZY_EXPORT long NAME() { return 0; }` entry).
"""
import subprocess
import sys

out = subprocess.run(["readelf", "--dyn-syms", "-W", sys.argv[1]], capture_output=True, text=True, check=True).stdout
for line in out.splitlines():
    cols = line.split()
    if len(cols) >= 8 and cols[6] == "UND" and cols[7].startswith("Discord_"):
        print(cols[7])
