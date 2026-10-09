# Zhuyin T9 core, 6.82

The APK's positive-frequency, structurally valid single-character readings determine the inventory. The key map, legality, static frequency rank and character checks live here; the phone and staged server both use this crate. `*` is a forced syllable boundary, never a wire character. A code prefix represents all attested syllable hypotheses with that prefix, so the trie bitset can collapse identical integer prefixes.

Inputs: `app/src/phone/assets/zhuyin_initials.db` plus missing-character readings extracted from the **exact bundled** `terra_pinyin.table.bin`. `extract_rime.py` copies the binary into `out/`, runs the existing Rime decompiler there and applies official rime-bopomofo spelling algebra. It never edits the original dictionary. Supplement rows are committed, hash-pinned in MANIFEST and reproducible. The generated legal inventory currently contains 415 toneless syllables, 48 codes and 18,919 characters. These counts supersede the article's unmeasured 412.

The dictionary data derives from Rime Terra Pinyin / MDBG CC-CEDICT, whose upstream notice is CC BY-SA 3.0; the supplemented/generated dictionary data retains that attribution and license. Official spelling algebra is from RIME Developers' rime-bopomofo. Existing repository assets and Rime runtime keep their upstream licenses.

```sh
python3 native/zhuyin_t9_core/extract_rime.py
cargo test --offline --manifest-path native/zhuyin_t9_core/Cargo.toml
python3 native/zhuyin_t9_core/tests/rank.py
cargo build --release --offline --manifest-path native/zhuyin_t9_core/Cargo.toml
native/zhuyin_t9_core/target/release/t9core encode 聲請條款
native/zhuyin_t9_core/target/release/t9core check 聲請條款 '50 470 279 370'
native/zhuyin_t9_core/target/release/t9core fsm-replay '50 470 279 370'
bash scripts/build_t9_core.sh
```

`build.rs` regenerates inventory for Rust builds. Android's Gradle prerequisite also re-extracts the bundled table before cargo-ndk and verifies each copied ABI `.so` against the fresh build. `RIME_HOST_PREFIX` and `RIME_SOURCE_DATA` refer to the existing locally installed Rime toolchain/data; nothing downloads a dictionary during input.

Resolved SPEC ambiguity: its general complete-prefix rule permits starting zero-initial `7`, but its explicit `47` acceptance example forbids `7`. The implementation requires `*` before another medial-only syllable following a medial-ending prefix; its oracle and replay receipts explicitly include that decision. Polyphonic personal syllable learning uses the highest-frequency attested reading for the committed character under the chosen integer code. Character rank caches invalidate when local learning preferences change; no personal data is bundled or sent as telemetry.

Phone runtime uses the existing ARM64 Rime stack, including X1's native bridge. Rust also builds x86_64. The public phone APK retains the existing ARM64 ABI filter because adding a primary x86_64 ABI without x86_64 Rime would break existing Rime loading. Native x86_64 packaging is an explicit review item rather than a falsely claimed X1 native-ABI pass.
