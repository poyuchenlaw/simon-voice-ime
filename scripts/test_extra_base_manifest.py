#!/usr/bin/env python3
import json
import sys

manifest = json.load(open(sys.argv[1], encoding="utf-8"))
records = manifest.get("patches", [])
assert any(record.get("from_versionCode") == 73 for record in records), records
print("versionCode-73 patch record present")
