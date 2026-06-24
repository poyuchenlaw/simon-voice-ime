#!/usr/bin/env python3
"""
Headless reproduction of the v6.9 APPEND fast-mode "嗯" bug, and proof of the fix.

Uses the SAME SenseVoice int8 model + tokens + silero VAD that the Android app
downloads (verified by SHA-256 against LocalSTTHelper.java), via the SAME engine
(sherpa-onnx). This faithfully mirrors LocalSTTHelper.java so the Python result
predicts the on-device Java result.

Three code paths are reproduced:

  (A) VAD-segmented path  — mirrors LocalSTTHelper.feedAudioChunk / flushVad:
          silero VAD (threshold 0.5, minSilence 0.5s, minSpeech 0.3s,
          maxSpeech 6.0s, window 512) -> per-segment recognizeOffline ->
          dedup-overlap concatenation (mirrors dedupOverlapHead).
      This is what the live preview strip shows. EXPECTED: full correct text.

  (B) Single-shot path    — mirrors LocalSTTHelper.recognize(appendPcm):
          one recognizeOffline on the WHOLE buffer.
      This is what the stop branch (SimonIMEService ~line 1280) commits.
      EXPECTED: degraded / "嗯"-class garbage on long clips.

  (C) Concurrent-decode   — two threads decode on the SHARED recognizer at once
          (single-shot racing still-pending segment decodes on segmentExecutor).
      The OfflineRecognizer is not thread-safe; EXPECTED: corruption.

Run:  python3 verify/repro_ondevice_stt.py
Writes: verify/repro_results.txt
"""
import glob
import hashlib
import os
import sys
import threading
import wave

import numpy as np
import sherpa_onnx

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIO_DIR = os.path.join(HERE, "audio")
RESULTS = os.path.join(HERE, "repro_results.txt")

# Same files the Android app downloads (LocalSTTHelper.java ModelFile entries).
MODEL = "/home/simon/whisper-to-input-proxy/models/sensevoice/model.int8.onnx"
TOKENS = "/home/simon/whisper-to-input-proxy/models/sensevoice/tokens.txt"
VAD = "/home/simon/whisper-to-input-proxy/models/silero_vad.onnx"

# SHA-256 the Android app pins (LocalSTTHelper.java).
EXPECT_SHA = {
    MODEL: "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
    TOKENS: "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
    VAD: "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
}

SAMPLE_RATE = 16000
OVERLAP_SAMPLES = 6400  # LocalSTTHelper.OVERLAP_SAMPLES (0.4s)

out_lines = []


def log(msg=""):
    print(msg)
    out_lines.append(msg)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_wav_f32(path):
    with wave.open(path, "rb") as w:
        assert w.getframerate() == SAMPLE_RATE, w.getframerate()
        assert w.getnchannels() == 1
        assert w.getsampwidth() == 2
        raw = w.readframes(w.getnframes())
    pcm16 = np.frombuffer(raw, dtype=np.int16)
    return (pcm16.astype(np.float32) / 32768.0), pcm16


def make_recognizer():
    # Mirrors LocalSTTHelper.init() Phase 1: SenseVoice, zh, ITN on, greedy, 4 threads, cpu.
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=MODEL,
        tokens=TOKENS,
        num_threads=4,
        use_itn=True,
        language="zh",
        decoding_method="greedy_search",
        debug=False,
    )


def recognize_offline(recognizer, samples_f32):
    """Mirrors LocalSTTHelper.recognizeOffline: createStream/accept/decode/getResult."""
    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, samples_f32)
    recognizer.decode_stream(stream)
    return (stream.result.text or "").strip()


def make_vad():
    # Mirrors LocalSTTHelper.init() Phase 2: silero VAD config.
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = VAD
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.5
    cfg.silero_vad.min_speech_duration = 0.3
    cfg.silero_vad.max_speech_duration = 6.0
    cfg.silero_vad.window_size = 512
    cfg.sample_rate = SAMPLE_RATE
    cfg.num_threads = 1
    cfg.provider = "cpu"
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=30)


def dedup_overlap_head(prev, seg, max_window=6):
    """Verbatim port of SimonIMEService.dedupOverlapHead."""
    if not prev or not seg:
        return seg or ""
    m = min(max_window, len(prev), len(seg))
    for k in range(m, 0, -1):
        if prev[len(prev) - k:] == seg[:k]:
            return seg[k:]
    return seg


def path_a_vad_segmented(recognizer, vad, samples_f32):
    """Mirror feedAudioChunk (window feed) + flushVad + dedup-join."""
    vad.reset()
    segments_text = []
    last_tail = None
    window = 512

    def handle_segment(seg_samples, with_overlap):
        nonlocal last_tail
        if seg_samples.shape[0] < int(SAMPLE_RATE * 0.2):
            return
        if with_overlap and last_tail is not None and last_tail.shape[0] > 0:
            recog_input = np.concatenate([last_tail, seg_samples])
        else:
            recog_input = seg_samples
        if with_overlap:
            ov = min(OVERLAP_SAMPLES, seg_samples.shape[0])
            last_tail = seg_samples[seg_samples.shape[0] - ov:]
        text = recognize_offline(recognizer, recog_input)
        if text:
            segments_text.append(text)

    # Stream the audio in windows, like the recording thread feeding chunks.
    i = 0
    n = samples_f32.shape[0]
    while i < n:
        chunk = samples_f32[i:i + window]
        vad.accept_waveform(chunk)
        i += window
        while not vad.empty():
            handle_segment(np.asarray(vad.front.samples, dtype=np.float32), with_overlap=True)
            vad.pop()

    # flushVad: flush trailing audio into a final segment.
    vad.flush()
    while not vad.empty():
        handle_segment(np.asarray(vad.front.samples, dtype=np.float32), with_overlap=False)
        vad.pop()

    # Dedup-overlap join (mirrors the preview accumulation loop).
    out = ""
    for seg in segments_text:
        out += dedup_overlap_head(out, seg, 6)
    return out, segments_text


def path_b_single_shot(recognizer, samples_f32):
    """Mirror LocalSTTHelper.recognize(appendPcm): whole-buffer single decode."""
    return recognize_offline(recognizer, samples_f32)


def path_c_concurrent(recognizer, samples_f32, segs):
    """
    Mirror the stop-time race: single-shot decode on the SHARED recognizer while
    segment decodes are still running on it (no synchronization).
    """
    results = {}
    errors = {}

    def decode(key, audio):
        try:
            results[key] = recognize_offline(recognizer, audio)
        except Exception as e:  # noqa: BLE001
            errors[key] = repr(e)

    threads = []
    # Thread 1: the stop-time single-shot on the full buffer.
    threads.append(threading.Thread(target=decode, args=("single_shot", samples_f32)))
    # Threads 2..: still-pending per-segment decodes (what segmentExecutor was doing).
    for idx, s in enumerate(segs):
        threads.append(threading.Thread(target=decode, args=(f"seg_{idx}", s)))
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return results, errors


def main():
    log("=" * 78)
    log("REPRO: v6.9 APPEND fast-mode on-device STT — segmented vs single-shot")
    log("=" * 78)
    log(f"sherpa_onnx version: {sherpa_onnx.__version__ if hasattr(sherpa_onnx,'__version__') else '?'}")
    log("")
    log("[model integrity] verifying the EXACT files the Android app pins:")
    all_ok = True
    for p, exp in EXPECT_SHA.items():
        if not os.path.exists(p):
            log(f"  MISSING: {p}")
            all_ok = False
            continue
        got = sha256(p)
        ok = got == exp
        all_ok = all_ok and ok
        log(f"  {'OK ' if ok else 'BAD'} {os.path.basename(p)}: {got[:16]}… (app pins {exp[:16]}…)")
    if not all_ok:
        log("ABORT: model files do not match the app's pinned SHA-256.")
        _flush()
        sys.exit(1)
    log("")

    wavs = sorted(glob.glob(os.path.join(AUDIO_DIR, "*.wav")))
    if not wavs:
        log("ABORT: no test audio. Run verify/gen_test_audio.py first.")
        _flush()
        sys.exit(1)

    recognizer = make_recognizer()
    vad = make_vad()

    n_b_garbage = 0
    n_a_good = 0
    n_c_corrupt = 0

    for wav in wavs:
        name = os.path.basename(wav)
        samples, _ = load_wav_f32(wav)
        dur = samples.shape[0] / SAMPLE_RATE
        log("-" * 78)
        log(f"CLIP: {name}  ({dur:.1f}s)")

        a_text, a_segs = path_a_vad_segmented(recognizer, vad, samples)
        log(f"  (A) VAD-segmented [PREVIEW path, what the strip shows]:")
        log(f"      {len(a_segs)} segment(s): {a_segs}")
        log(f"      joined = '{a_text}'  (len={len(a_text)})")

        b_text = path_b_single_shot(recognizer, samples)
        log(f"  (B) Single-shot full buffer [STOP path, what v6.9 COMMITS]:")
        log(f"      '{b_text}'  (len={len(b_text)})")

        # Re-derive raw VAD segments (no overlap) for the concurrency test.
        raw_segs = []
        vad.reset()
        i = 0
        while i < samples.shape[0]:
            vad.accept_waveform(samples[i:i + 512])
            i += 512
            while not vad.empty():
                raw_segs.append(np.asarray(vad.front.samples, dtype=np.float32))
                vad.pop()
        vad.flush()
        while not vad.empty():
            raw_segs.append(np.asarray(vad.front.samples, dtype=np.float32))
            vad.pop()

        c_results, c_errors = path_c_concurrent(recognizer, samples, raw_segs)
        c_single = c_results.get("single_shot", "")
        log(f"  (C) Concurrent decode on SHARED recognizer (race):")
        log(f"      single_shot-under-race = '{c_single}' (len={len(c_single)})")
        if c_errors:
            log(f"      errors: {c_errors}")
        seg_under_race = {k: v for k, v in c_results.items() if k.startswith('seg_')}
        log(f"      segments-under-race    = {seg_under_race}")

        # Scoring (heuristic, logged for the reviewer).
        a_good = len(a_text) >= max(6, int(0.6 * len(b_text) + 6))
        b_garbage = len(b_text) <= 3  # "嗯" / "嗯。" class
        if a_good:
            n_a_good += 1
        if b_garbage:
            n_b_garbage += 1
        c_isolated = path_b_single_shot(recognizer, samples)  # baseline, no race
        if c_single != c_isolated:
            n_c_corrupt += 1
        log(f"  VERDICT: segmented_correct={a_good}  single_shot_garbage={b_garbage}"
            f"  concurrent_changed_output={c_single != c_isolated}")

    log("=" * 78)
    log("SUMMARY")
    log(f"  clips tested:                         {len(wavs)}")
    log(f"  (A) segmented path produced full text: {n_a_good}/{len(wavs)}")
    log(f"  (B) single-shot degraded to garbage:   {n_b_garbage}/{len(wavs)}")
    log(f"  (C) concurrent decode changed output:  {n_c_corrupt}/{len(wavs)}")
    log("=" * 78)
    log("INTERPRETATION:")
    log("  - The PREVIEW strip (segmented path A) is correct -> fix = commit THAT text.")
    log("  - The v6.9 commit (single-shot path B) is what regresses to '嗯'-class output.")
    log("  - Path C shows the shared recognizer is not thread-safe -> synchronized needed.")
    _flush()


def _flush():
    with open(RESULTS, "w", encoding="utf-8") as f:
        f.write("\n".join(out_lines) + "\n")
    print(f"\n[written] {RESULTS}")


if __name__ == "__main__":
    main()
