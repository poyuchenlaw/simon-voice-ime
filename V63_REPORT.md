# v6.3 Report: Server Failure -> On-device SenseVoice Fallback

## Summary

Implemented APPEND-mode offline fallback so a server-path failure no longer ends with no output. When WebSocket finalization or HTTP transcription fails, the IME now uses the existing `fullPcmBuffer` and on-device `LocalSTTHelper` SenseVoice recognition, then commits the local result once with `InputConnection.commitText`.

## Changed Files

- `app/src/main/java/com/simon/voiceime/SimonIMEService.java`
  - Lines 938-950: `auth_fail` is now treated as a server-path failure. While still recording it keeps local buffering; after recording it starts fallback.
  - Lines 995-1008: WebSocket `error` is now treated the same way.
  - Lines 1403-1438: APPEND HTTP upload failures/non-2xx/empty text/parse errors can trigger offline fallback.
  - Lines 1572-1578: APPEND HTTP success commit is guarded with `utteranceFinished.compareAndSet(false, true)`.
  - Lines 1908-1918: `httpFallbackFullAudio()` still acquires the single-utterance guard, then calls the new APPEND-specific HTTP fallback.
  - Lines 1925-1979: added `sendFullAudioHttpFallback(...)` for `/v1/audio/transcriptions`; failures fall through to local SenseVoice.
  - Lines 1982-2028: added `runOfflineFullAudioFallback(...)`, which runs SenseVoice on full PCM and commits only non-empty text.
- `app/build.gradle`
  - `versionCode 37`
  - `versionName "6.3"`

## Fallback Trigger Conditions

Offline SenseVoice fallback is triggered only after the server path is determined failed:

- WebSocket `final` text is empty -> full-audio HTTP fallback -> offline if HTTP fails/empty.
- WebSocket `onFailure` after recording stopped -> full-audio HTTP fallback -> offline if HTTP fails/empty.
- WebSocket failure while recording -> keep recording into `fullPcmBuffer`; on stop -> full-audio HTTP fallback -> offline if HTTP fails/empty.
- WebSocket `auth_fail` or `error` -> same failure handling.
- APPEND direct HTTP upload failure/non-2xx/empty/parse error -> offline fallback from `fullPcmBuffer`.

Local fallback refuses to commit error strings. It commits only when SenseVoice returns non-empty text; otherwise it logs and shows a status message.

## Single-commit Guard

The existing `utteranceFinished` `AtomicBoolean` remains the single finalization guard:

- Normal WebSocket final commit still uses `compareAndSet(false, true)`.
- HTTP APPEND success commit now also uses `compareAndSet(false, true)`.
- `httpFallbackFullAudio()` reserves the utterance before HTTP fallback, so late WebSocket callbacks cannot double-commit.
- Offline fallback either uses the already reserved fallback path or acquires the same guard before committing.

## WS Normal Path

No intended behavior change to the normal successful WebSocket path:

- Non-empty WebSocket `final` still commits once via `commitText`.
- No `setComposingText` was added.
- Streaming preview remains in the keyboard preview strip, not the target input field.

## Build / Verification

- Java type check for modified `SimonIMEService.java`: PASS
  - Command used a local `javac` classpath from existing Android/Gradle intermediates and completed with exit code 0.
- Full Gradle APK build: BLOCKED by the current Codex sandbox, not by Java compilation.
  - Specified command first failed because Gradle could not create a lock under `/home/simon/.gradle`.
  - Retrying with `GRADLE_USER_HOME=/tmp/...` failed because wrapper download/network sockets are denied: `java.net.SocketException: Operation not permitted`.
  - Retrying with local Gradle 8.5 distribution failed because Gradle's single-use daemon needs a local socket, also denied: `java.net.SocketException: Operation not permitted`.

APK status:

- No verified v6.3 APK was produced in this sandbox.
- Existing `app/build/outputs/apk/debug/app-debug.apk` is a stale pre-existing artifact:
  - Size: `286253370` bytes
  - Timestamp: `2026-06-15 19:05:20 +0800`
  - Do not treat it as the v6.3 build output.

