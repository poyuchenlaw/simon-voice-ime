#!/usr/bin/env python3
"""
Generate test audio WITH explicit silence gaps between clauses, so the silero
VAD (min_silence_duration=0.5s) splits each utterance into MULTIPLE segments —
the real-phone condition where several per-segment decodes are still pending on
segmentExecutor when the stop-time single-shot fires on the shared recognizer.

Also produces a single concatenated long clip (all clauses, ~30s+) to stress the
single-shot full-buffer decode the way a long dictation does on the phone.

Output: 16kHz mono s16 WAV.  Run: python3 verify/gen_gapped_audio.py
"""
import asyncio
import os
import subprocess

import edge_tts

VOICE = "zh-TW-HsiaoChenNeural"
OUT_DIR = os.path.join(os.path.dirname(__file__), "audio")
GAP_SEC = 0.9  # > VAD min_silence_duration (0.5s) -> forces a segment boundary

# Each entry = list of clauses spoken with a silence gap between them.
GAPPED = {
    "gapped_legal": [
        "鈞院明鑒",
        "被告所辯挪用款項一節",
        "與卷內存摺往來明細不符",
        "請求依法駁回原告之訴",
    ],
    "gapped_dictation": [
        "今天下午三點要跟客戶開會",
        "討論遺產分割協議的細節",
        "記得把估價報告印出來帶過去",
        "順便確認對造的答辯狀有沒有送到",
    ],
}


def synth(text, mp3):
    asyncio.run(edge_tts.Communicate(text, VOICE).save(mp3))


def to_wav(src, dst):
    subprocess.run(["ffmpeg", "-y", "-i", src, "-ar", "16000", "-ac", "1",
                    "-sample_fmt", "s16", dst], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def silence_wav(path, seconds):
    subprocess.run(["ffmpeg", "-y", "-f", "lavfi", "-i",
                    f"anullsrc=r=16000:cl=mono", "-t", str(seconds),
                    "-sample_fmt", "s16", path], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def concat(parts, dst):
    listf = dst + ".txt"
    with open(listf, "w") as f:
        for p in parts:
            f.write(f"file '{os.path.abspath(p)}'\n")
    subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listf,
                    "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", dst],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.remove(listf)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    gap = os.path.join(OUT_DIR, "_gap.wav")
    silence_wav(gap, GAP_SEC)

    long_parts = []
    for name, clauses in GAPPED.items():
        parts = []
        for i, clause in enumerate(clauses):
            mp3 = os.path.join(OUT_DIR, f"{name}_{i}.mp3")
            wav = os.path.join(OUT_DIR, f"{name}_{i}.wav")
            synth(clause, mp3)
            to_wav(mp3, wav)
            parts.append(wav)
            if i < len(clauses) - 1:
                parts.append(gap)
        out = os.path.join(OUT_DIR, name + ".wav")
        concat(parts, out)
        long_parts.extend(parts)
        long_parts.append(gap)
        print(f"[gen] {name}: {len(clauses)} clauses, {os.path.getsize(out)} bytes")

    # One long combined clip (~30s) for single-shot stress.
    long_out = os.path.join(OUT_DIR, "gapped_long.wav")
    concat(long_parts, long_out)
    dur = os.path.getsize(long_out) / (16000 * 2)
    print(f"[gen] gapped_long: ~{dur:.0f}s, {os.path.getsize(long_out)} bytes")
    print("done")


if __name__ == "__main__":
    main()
