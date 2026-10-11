"""Read back every dual-hand manifest fingerprint; no asset regeneration."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/phone/assets/dual_hand"
manifest = json.loads((assets / "MANIFEST.json").read_text())
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(root / "native/dual_hand/generate.py") == manifest["generator_sha256"]
for name, expected in manifest["sources"].items():
    assert sha(root / name) == expected, name
for name, expected in manifest["artifacts"].items():
    assert sha(assets / name) == expected["sha256"], name
    assert (assets / name).stat().st_size == expected["bytes"], name
print("PASS: generator, all 8 source hashes, all 6 artifact hashes and byte counts")
