#!/usr/bin/env python3
"""
Stress reproduction: the two real-phone failure conditions that the clean
single-clip harness could NOT trigger:

  (1) MULTI-SEGMENT audio (silence gaps -> VAD splits) so several per-segment
      decodes are genuinely pending at stop time.
  (2) CONCURRENT decode contention on the ONE shared OfflineRecognizer
      (stop-time single-shot racing the still-pending segment decodes), run for
      many trials, because the corruption is a data race (nondeterministic).

Also reproduces the downstream "嗯" cascade meaning: if the committed text is a
filler token, OnDeviceCorrector strips it -> empty -> server fallback.

Run: python3 verify/repro_stress.py   (writes verify/repro_stress_results.txt)
"""
import concurrent.futures
import glob
import os
import threading
import wave

import numpy as np
import sherpa_onnx

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIO_DIR = os.path.join(HERE, "audio")
RESULTS = os.path.join(HERE, "repro_stress_results.txt")

MODEL = "/home/simon/whisper-to-input-proxy/models/sensevoice/model.int8.onnx"
TOKENS = "/home/simon/whisper-to-input-proxy/models/sensevoice/tokens.txt"
VAD = "/home/simon/whisper-to-input-proxy/models/silero_vad.onnx"
SR = 16000

out = []


def log(m=""):
    print(m)
    out.append(m)


def wav_f32(path):
    with wave.open(path, "rb") as w:
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


def mk_rec():
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=MODEL, tokens=TOKENS, num_threads=4, use_itn=True,
        language="zh", decoding_method="greedy_search", debug=False)


def mk_vad():
    c = sherpa_onnx.VadModelConfig()
    c.silero_vad.model = VAD
    c.silero_vad.threshold = 0.5
    c.silero_vad.min_silence_duration = 0.5
    c.silero_vad.min_speech_duration = 0.3
    c.silero_vad.max_speech_duration = 6.0
    c.silero_vad.window_size = 512
    c.sample_rate = SR
    c.num_threads = 1
    c.provider = "cpu"
    return sherpa_onnx.VoiceActivityDetector(c, buffer_size_in_seconds=60)


def rec_off(rec, samples):
    s = rec.create_stream()
    s.accept_waveform(SR, samples)
    rec.decode_stream(s)
    return (s.result.text or "").strip()


def segments(vad, samples):
    vad.reset()
    segs = []
    i = 0
    while i < samples.shape[0]:
        vad.accept_waveform(samples[i:i + 512])
        i += 512
        while not vad.empty():
            segs.append(np.asarray(vad.front.samples, dtype=np.float32))
            vad.pop()
    vad.flush()
    while not vad.empty():
        segs.append(np.asarray(vad.front.samples, dtype=np.float32))
        vad.pop()
    return segs


def main():
    log("=" * 78)
    log("STRESS REPRO: multi-segment VAD + concurrent shared-recognizer race")
    log("=" * 78)

    rec = mk_rec()
    vad = mk_vad()

    # ---- Part 1: multi-segment VAD on gapped clips ----
    log("\n--- Part 1: gapped clips split into MULTIPLE segments ---")
    gapped = sorted(glob.glob(os.path.join(AUDIO_DIR, "gapped_*.wav")))
    for wav in gapped:
        name = os.path.basename(wav)
        samples = wav_f32(wav)
        segs = segments(vad, samples)
        seg_texts = [rec_off(rec, s) for s in segs]
        joined = "".join(t for t in seg_texts if t)
        single = rec_off(rec, samples)
        log(f"  {name} ({samples.shape[0]/SR:.0f}s): {len(segs)} segment(s)")
        log(f"    segments      = {seg_texts}")
        log(f"    seg-joined    = '{joined}' (len={len(joined)})")
        log(f"    single-shot   = '{single}' (len={len(single)})")

    # ---- Part 2: concurrency stress on the SHARED recognizer ----
    # Reproduce the stop-time race: single-shot full buffer decoded on the SAME
    # recognizer object while N segment decodes run concurrently. Repeat T trials
    # because a data race is nondeterministic. We compare each run's single-shot
    # result against the ISOLATED (no-race) baseline.
    log("\n--- Part 2: concurrent decode on ONE shared recognizer (race) ---")
    long_wav = os.path.join(AUDIO_DIR, "gapped_long.wav")
    samples = wav_f32(long_wav)
    segs = segments(vad, samples)
    baseline = rec_off(rec, samples)
    log(f"  clip: gapped_long ({samples.shape[0]/SR:.0f}s), {len(segs)} segments")
    log(f"  ISOLATED single-shot baseline = '{baseline}' (len={len(baseline)})")

    TRIALS = 12
    corrupted = 0
    errored = 0
    empties = 0
    examples = []
    for trial in range(TRIALS):
        results = {}
        errs = {}

        def decode(key, audio):
            try:
                results[key] = rec_off(rec, audio)
            except Exception as e:  # noqa: BLE001
                errs[key] = repr(e)

        threads = [threading.Thread(target=decode, args=("single", samples))]
        for j, s in enumerate(segs):
            threads.append(threading.Thread(target=decode, args=(f"s{j}", s)))
        # interleave start order to maximize contention
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        single = results.get("single", "")
        if errs:
            errored += 1
            if len(examples) < 4:
                examples.append(f"trial {trial}: ERROR {errs}")
        if single == "":
            empties += 1
        if single != baseline:
            corrupted += 1
            if len(examples) < 6:
                examples.append(f"trial {trial}: single='{single}' (len={len(single)}) vs baseline len={len(baseline)}")

    log(f"  trials={TRIALS}  single-shot != baseline: {corrupted}/{TRIALS}"
        f"  errors: {errored}/{TRIALS}  empty: {empties}/{TRIALS}")
    for ex in examples:
        log(f"    {ex}")

    # ---- Part 3: also stress per-segment outputs under race (preview path) ----
    log("\n--- Part 3: do per-segment decodes themselves corrupt under race? ---")
    seg_baseline = [rec_off(rec, s) for s in segs]
    seg_corrupt = 0
    for trial in range(TRIALS):
        results = {}

        def decode(key, audio):
            results[key] = rec_off(rec, audio)

        threads = [threading.Thread(target=decode, args=("single", samples))]
        for j, s in enumerate(segs):
            threads.append(threading.Thread(target=decode, args=(f"s{j}", s)))
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        for j in range(len(segs)):
            if results.get(f"s{j}", "<missing>") != seg_baseline[j]:
                seg_corrupt += 1
                break
    log(f"  trials={TRIALS}  any segment differs from isolated baseline: {seg_corrupt}/{TRIALS}")

    log("\n" + "=" * 78)
    log("CONCLUSION")
    log("  If Part 2/3 show >0 corruption/errors, the shared OfflineRecognizer is")
    log("  not thread-safe -> stop-time single-shot racing pending segment decodes")
    log("  can corrupt output. synchronized(recognizeOffline) is required.")
    log("  Regardless: the segmented PREVIEW text is the reliable source to commit.")
    log("=" * 78)

    with open(RESULTS, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print(f"\n[written] {RESULTS}")


if __name__ == "__main__":
    main()
