# SPEC v6.44 — zhuyin segmentation rule, raw-letter safety, and release gates (spec-level review after the 6.42 incident)

Date 2026-09-30 ~15:40. Author: Cymon. Reviewer: Gemini (required before implementation). Implementer: Codex.
Source: Simon 15:4x 「為什麼注音鍵盤會打出英文字來，Xㄛ日Xeulepsugjieeee」 and 「請自己在規格層次確認不要掛一漏萬」.

## 0. What went wrong, at the spec level (not only the code)
1. The 掉 rule was handed over as prose ("abbreviation only when the following symbols cannot extend the initial") and implemented as a narrower proxy ("abbreviation only when the next symbol is another initial"). Nothing checked the proxy against the rule. ㄍ+ㄧ cannot form a syllable, so the only reading of Simon's ㄍ (果) was forbidden: no parse, and Rime passed the keys through as letters.
2. The tests were hand-picked sentences (a 掉 sentence, five medial variants). None used Simon's own typing, and he types first-letter shortcuts constantly (A4 already warned about this).
3. No invariant said "the zhuyin page never commits raw keysym letters". A wrong rule therefore produced garbage in his text instead of a visible zhuyin preview.
4. The early-release waiver dropped the emulator acceptance, and with it every behavioural check. Cheap host checks (schema replay over his real key history, a fuzz test) do not need the emulator and should never be waived.
5. 6.41's changes (nine-key page removal, privacy guard rename `protectedInputField`, touch offsets) reached Simon for the first time inside 6.42 and were never verified on any device path.

## 1. Segmentation rule (nine cells)
1. Purpose: whatever zhuyin Simon presses, in his style (toneless streaming, first-letter shortcuts, full syllables), the candidates include what he means; full syllables and shortcuts both work.
2. Judgement: which parse wins is a ranking decision (engine scoring, personal words). Hard rules are limited to: (a) the syllable inventory, generated from the dictionary data, never hand-written; (b) the raw-letter invariant in section 2.
3. Graded differences: no rule may delete a parse that is possible. A shortcut parse and a full-syllable parse must both exist whenever both are possible; ranking chooses. Only impossible parses are absent.
4. Invariants: (i) every prefix of a zhuyin key sequence has at least one parse; (ii) when the presses form a valid full syllable (掉 = ㄉㄧㄠˋ), that syllable is among the candidates; (iii) Simon's logged first-letter shortcuts keep their top-5 hit rate (A4).
5. Exception means: a sequence that fails means the parse space or the ranking is wrong at the generator level; fix the generator or ranking, never add a per-word case.
6. When unsure: if the engine returns no parse, keep the zhuyin symbols visible in the preview (editable, deletable), log a `no_parse` event with the key sequence (no text), never commit letters.
7. Generalisation: any rule that narrows a search space (speller algebra, filters, guards, candidate caps) can silently remove the right answer (same failure family as tonight's voice candidate generator, which offered the right word for only 11 of 22 errors). Such rules are verified against the full real-usage corpus for "no result" and "right answer missing", not against examples.
8. Rollback: if corpus replay or Simon reports raw letters or missing shortcuts, restore the three schema files to 391689c (the 6.43 hotfix pattern). Owner Cymon.
9. Acceptance (re-runnable, with a prediction that could fail):
   a. Corpus replay: extract every key session from Simon's telemetry (device c7cb40d0, all dates, versions 6.37–6.42), split into composition windows (between commits/clears), replay every prefix through the packaged schema of the candidate build and of 6.41. Report zero-candidate prefixes (prediction: candidate build = 0 new versus 6.41) and, for each window that ended in a commit, whether the committed text is in the top 10 (prediction: no window lost versus 6.41).
   b. Generated pair table: for every initial × every following zhuyin symbol, and every initial alone at the end, at least one parse exists (prediction: 100%); the 6.42 schema must fail this table (it is the counterexample).
   c. 掉 cases: the telemetry 掉 sequence and the five medial variants offer the full syllable.
   d. A4: Simon's logged first-letter inputs (from telemetry) keep their top-5 hit rate versus 6.41 through the app's real path (`ZhuyinInputController` + `ZhuyinWordIndex`).
   e. Cross-family review of this spec and of the results (Gemini, inline recipe).

## 2. Raw-letter invariant (hard rule: data integrity)
On the zhuyin page, no ASCII keysym letter from the internal key map may ever be committed to the text field. Test: a host fuzz test feeds 10,000 random zhuyin key streams (including tones, backspace, comma, enter) through the controller and asserts no committed text contains [a-z0-9;,./-] that came from the key map; plus Simon's 15:35 sequence as a named case. Implementation choice is Codex's (reject the passthrough and keep the zhuyin preview).

## 3. Every change that reached Simon in 6.42 (and 6.43), with its invariant and check
| Change | Invariant | Check | Status |
|---|---|---|---|
| Comma key (tap, long press, flush) | tap never lost; pending composition committed first | real touch-path unit test; device check | host green; device pending |
| Vocabulary: public bundle | Traditional only; no private names | count-only scan of APK and repo | done for 6.42 |
| Vocabulary: private sync | auth only; offline keeps cache; caps; never blocks typing; learned words kept | unit tests + server down simulation | unit green; server-down not tested |
| First-press suggestions and ranking tiers | A4 shortcut hit rate unchanged | section 1.9d on real logged inputs | NOT DONE |
| Telemetry key event | all 6.41 fields plus key_to_candidate_ms | unit test | done |
| 6.41: nine-key page removal | no dead key or page; swipe order unchanged | layout test + corpus of page events | not verified on device |
| 6.41: privacy guard rename (protectedInputField) | no logging, upload or learning in password and other protected fields | unit test of `isProtectedInputField` over password/visible-password/web-password/number-password/no-personalized-learning inputs; 6.42 learned-word store and private sync must skip protected fields | PARTIAL (orchestrator 15:39): telemetry events collapse to a `protected_field_skipped` marker (Proto3DiagnosticsTest) and touch learning is suppressed (source check in NoT9FootprintTest); field-type detection and the new learned-word path are NOT tested — security gate for 6.44 |
| 6.41: touch offsets kept | no change in key registration | telemetry offsets within key bounds (orchestrator check on 6.40: ±25 px) | recheck on 6.43 telemetry |
| B12 schema (reverted in 6.43) | section 1 | section 1.9 | replaced by this spec |

## 4. Release gates that are never waived (even for early test builds)
Host-only, minutes, no emulator: (1) full unit suite; (2) corpus replay 1.9a on the packaged schema; (3) generated pair table 1.9b; (4) raw-letter fuzz test; (5) privacy guard unit test; (6) private-name and secret scans; (7) signing and patch restore. The emulator acceptance may be waived only by Simon, per build.

## 4b. Round-1 review resolution (Gemini CONDITIONAL_GO, `GEMINI_spec_v644_r1.md`; orchestrator rulings 2026-09-30 16:00)
1. Speller algebra (their 1): ACCEPT the intent, with a chosen design: keep 6.41's abbreviation derivations exactly (unconstrained initial-only abbreviation, as in 391689c); no derivation may be removed. The 掉 problem is solved by ranking, not by narrowing: when a complete-syllable parse covers the same presses as an abbreviation parse, the complete-syllable candidate must appear in the first visible candidate row (e.g. a Rime weight/penalty on abbreviated spellings, or the app's candidate merge in `ZhuyinInputController`); Codex picks the mechanism and states it. Multi-syllable shortcut chaining stays in Rime as in 6.41.
2. `no_parse` state machine (their 2): ACCEPT as written: keep all zhuyin glyphs in the composing preview; Backspace removes one glyph; Enter/Space commit the zhuyin glyphs (never ASCII); never commit a partial valid prefix while dropping or converting the tail.
3. Key-handling barrier (their 3): ACCEPT: on the zhuyin page every key event is consumed by the IME; no path falls back to default key handling that emits keysym letters, including on exceptions.
4. N-gram table (their 4): ACCEPT: 1.9b covers initial × next symbol, initial+medial+final+next symbol, and shortcut-to-shortcut transitions over the syllable inventory generated from the dictionary.
5. Controller-level fuzz (their 5): ACCEPT: section 2's fuzz runs through `ZhuyinInputController` and a mock `InputConnection` (Robolectric or equivalent), not only the schema; never waived.
6. Protected-field security gate (their 6): ACCEPT: zero writes to the learned-word store, zero private-sync payloads, zero telemetry content when `isProtectedInputField` is true; plus a unit test of `isProtectedInputField` over the input types in section 3; never waived.
7. Corpus includes abandoned sessions (their 7): ACCEPT: windows ending in clear/backspace-to-empty are replayed too; report zero-parse prefixes there.
8. Latency and rollback (their 8): PARTIAL: host replay timing is not phone latency, so the gate is relative: host replay p95 per key for the candidate build ≤ 6.43's + 10 ms; the absolute 50 ms (A10) is checked on Simon's phone from `key_to_candidate_ms` after install. Rollback scope includes schema and controller changes (revert commit of the 6.44 change set).

## 5. Feedback loop
Simon's reports → pull his telemetry for that time → add the exact sequence to the corpus test → fix at generator/ranking level → release. The corpus grows with every report; it is the regression suite.
