#!/usr/bin/env python3
"""
Generate Taiwanese (zh-TW) legal/dictation test audio via edge-tts.

These are multi-clause sentences typical of Simon's legal dictation use case.
Output: 16kHz mono 16-bit PCM WAV (matches the IME capture format SAMPLE_RATE=16000).

Run:  python3 verify/gen_test_audio.py
"""
import asyncio
import os
import subprocess
import sys

import edge_tts

VOICE = "zh-TW-HsiaoChenNeural"
OUT_DIR = os.path.join(os.path.dirname(__file__), "audio")

# Multi-clause legal/dictation sentences (deliberately long, with internal
# pauses, so the VAD splits them into several segments — the exact condition
# that makes the single-shot full-buffer decode degrade).
SENTENCES = {
    "legal_1": "鈞院明鑒，被告所辯挪用款項一節，與卷內存摺往來明細不符，請求依法駁回原告之訴。",
    "legal_2": "本件爭點在於系爭土地是否為袋地，按民法第七百八十七條規定，須以客觀通行是否適宜為判斷標準。",
    "dictation_1": "今天下午三點要跟客戶開會，討論遺產分割協議的細節，記得把估價報告印出來帶過去。",
    "dictation_2": "原告主張其代墊醫療費用共計新台幣兩百萬零五千九百零一元，惟其中健保給付部分應予扣除。",
}


def synth(text: str, mp3_path: str) -> None:
    async def _run():
        communicate = edge_tts.Communicate(text, VOICE)
        await communicate.save(mp3_path)
    asyncio.run(_run())


def to_wav_16k_mono(mp3_path: str, wav_path: str) -> None:
    # 16kHz mono s16le — identical to the IME audio capture pipeline.
    subprocess.run(
        ["ffmpeg", "-y", "-i", mp3_path, "-ar", "16000", "-ac", "1",
         "-sample_fmt", "s16", wav_path],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, text in SENTENCES.items():
        mp3 = os.path.join(OUT_DIR, name + ".mp3")
        wav = os.path.join(OUT_DIR, name + ".wav")
        print(f"[gen] {name}: {text}")
        synth(text, mp3)
        to_wav_16k_mono(mp3, wav)
        sz = os.path.getsize(wav)
        print(f"      -> {wav} ({sz} bytes)")
    print("done")


if __name__ == "__main__":
    main()
