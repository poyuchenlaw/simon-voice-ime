# Offline phonetic provenance retained for dual-hand W1

The nine-grid runtime, Rust/JNI library, schema, build tasks and tests were removed in W2.
This directory retains only W1's pinned input data and their original extraction/generation sources.
`native/dual_hand/generate.py` verifies hashes of `generate.py`, `extract_rime.py`,
`rime_single_chars.tsv`, `generated/legal_syllables.tsv`, `generated/code_to_syllables.tsv`
and `generated/MANIFEST.json` before consuming them. Other generated files remain as
manifest provenance; none of this directory is built into or shipped in the phone APK.
See `native/dual_hand/README.md` for the active data contract and reproduction command.
