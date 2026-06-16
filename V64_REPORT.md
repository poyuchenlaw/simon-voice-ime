# v6.4 Report: APPEND On-device Live Preview

## Summary

APPEND mode now feeds the same microphone PCM stream into the existing on-device `LocalSTTHelper.feedAudioChunk()` pipeline while continuing the existing WebSocket audio upload and server finalization path.

When SenseVoice returns a VAD segment, the app updates only the IME keyboard preview strip via `updatePreviewStrip("📱 " + tail)`. Server WebSocket chunk text is still collected/logged, but when on-device preview is available it no longer overwrites the preview strip. Final commit remains the existing server `final` path.

## Changed Files

- `app/src/main/java/com/simon/voiceime/SimonIMEService.java`
  - Lines 113-116: added APPEND on-device preview state and segment buffer.
  - Lines 805-807: each recording starts by preparing/resetting the on-device preview source.
  - Lines 846-848: APPEND recording loop now also feeds each PCM read to the on-device preview path.
  - Lines 974-978: WebSocket chunk preview is suppressed when on-device preview is active; WS chunks still remain in `streamedChunks`.
  - Lines 1028-1035: WebSocket failure during active on-device preview no longer clears the preview strip mid-recording.
  - Lines 1096-1103: stopping recording disables on-device preview and clears the keyboard preview strip as before.
  - Lines 1263-1324: added `prepareOnDeviceAppendPreview()` and `feedOnDeviceAppendPreview(...)`, including callback/feed error logging and fallback-disable behavior.
- `app/src/main/java/com/simon/voiceime/LocalSTTHelper.java`
  - Lines 129-149: existing `feedAudioChunk()` remains the VAD -> SenseVoice -> `onSegmentResult` interface used by v6.4.
  - Lines 247-260: `isStreamingReady()` gates preview availability; added `resetStreamingState()` so each utterance starts with a clean VAD buffer.
- `app/build.gradle`
  - Line 13: `versionCode 38`
  - Line 14: `versionName "6.4"`

## Preview Source

- Primary preview source in APPEND is now on-device SenseVoice when `localSTT.isStreamingReady()` is true.
- Audio flow during recording:
  - Existing path: PCM -> WebSocket `/ws/stream-audio` -> server final -> existing commit.
  - New visual-only path: PCM copy -> `LocalSTTHelper.feedAudioChunk()` -> VAD segment -> SenseVoice text -> `updatePreviewStrip()`.
- The preview tail still uses the existing 28-character rolling window behavior.
- If on-device streaming is unavailable, the previous WS chunk preview remains as fallback.

## Commit Path

No APPEND commit path was changed.

- Existing WebSocket final block remains at `SimonIMEService.java` lines 984-996.
- It still commits only server `finalText` via `utteranceFinished.compareAndSet(false, true)` -> `ic.commitText(finalText, 1)`.
- v6.3 HTTP/offline fallback code was not changed.
- The new on-device preview text is never used as final committed text.

## Input Field Safety

No preview text touches the target input field.

- No `setComposingText` call exists in executable code.
- New v6.4 methods do not call `getCurrentInputConnection()`.
- New v6.4 methods do not call `commitText()`.
- On-device preview updates only `previewText` through `updatePreviewStrip()`.

## Verification

- Ran static grep checks for `setComposingText`, `commitText`, `updatePreviewStrip`, and the new on-device preview methods.
- Reviewed `git diff` for the three changed files.
- Ran `git diff --check`: PASS.
- Did not run Gradle, per instruction.
- Did not commit, push, or create a release.
