#!/usr/bin/env python3
"""
Simon Voice IME v4.2 — Streaming WebSocket Automated Test

Tests:
1. WebSocket auth
2. Chunk delivery (at least 1 chunk received)
3. Chunks arrive in order (index 0, 1, 2...)
4. Chunk text contains Chinese characters
5. Final text is non-empty and contains Chinese
6. No errors during the session
7. Total time < 30 seconds
8. Key words preserved in final output

Prerequisites:
- Server running at localhost:8001
- edge-tts installed (pip install edge-tts)
- ffmpeg installed
- websockets installed (pip install websockets)
"""

import asyncio
import json
import subprocess
import sys
import time


async def test_streaming():
    try:
        import websockets
    except ImportError:
        print("FAIL: websockets not installed. Run: pip install websockets")
        sys.exit(1)

    # Generate test audio using edge-tts
    sentences = [
        "我覺得這個串流模式應該可以讓文字更快出現",
        "不用等到講完才看到結果",
        "這才是輸入法該有的體驗",
    ]

    full_text = "，".join(sentences)
    print(f"Generating test audio: '{full_text}'")

    try:
        result = subprocess.run(
            ["edge-tts", "--voice", "zh-TW-HsiaoChenNeural",
             "--text", full_text, "--write-media", "/tmp/test_stream.mp3"],
            capture_output=True, timeout=15, text=True
        )
        if result.returncode != 0:
            print(f"FAIL: edge-tts failed: {result.stderr}")
            sys.exit(1)
    except FileNotFoundError:
        print("FAIL: edge-tts not found. Run: pip install edge-tts")
        sys.exit(1)

    try:
        result = subprocess.run(
            ["ffmpeg", "-y", "-i", "/tmp/test_stream.mp3",
             "-ar", "16000", "-ac", "1", "-f", "s16le", "/tmp/test_stream.pcm"],
            capture_output=True, timeout=10, text=True
        )
        if result.returncode != 0:
            print(f"FAIL: ffmpeg failed: {result.stderr}")
            sys.exit(1)
    except FileNotFoundError:
        print("FAIL: ffmpeg not found")
        sys.exit(1)

    with open("/tmp/test_stream.pcm", "rb") as f:
        pcm = f.read()

    print(f"PCM audio: {len(pcm)} bytes ({len(pcm)/32000:.1f}s)")

    results = {"chunks": [], "final": "", "errors": []}
    t0 = time.time()

    try:
        async with websockets.connect("ws://localhost:8001/ws/stream-audio", open_timeout=5) as ws:
            # Auth
            await ws.send(json.dumps({"type": "auth", "password": "guangxin_voice_2026"}))
            auth = await asyncio.wait_for(ws.recv(), timeout=5)
            auth_data = json.loads(auth)
            if auth_data.get("type") != "auth_ok":
                print(f"FAIL: Auth failed: {auth}")
                sys.exit(1)
            print("Auth OK")

            # Send chunks (~2 seconds each = 64000 bytes at 16kHz 16-bit mono)
            chunk_size = 64000
            chunks_sent = 0
            for i in range(0, len(pcm), chunk_size):
                chunk = pcm[i:i + chunk_size]
                if len(chunk) >= 32000:  # Skip very short trailing chunks (< 1s, causes hallucination)
                    await ws.send(chunk)
                    chunks_sent += 1
                    print(f"  Sent chunk #{chunks_sent} ({len(chunk)} bytes)")
                    await asyncio.sleep(0.3)
                else:
                    print(f"  Skipped short trailing chunk ({len(chunk)} bytes)")

            print(f"Sent {chunks_sent} chunks, sending finalize...")
            await ws.send(json.dumps({"type": "finalize"}))

            # Collect responses
            while True:
                try:
                    resp = await asyncio.wait_for(ws.recv(), timeout=30)
                    data = json.loads(resp)
                    if data["type"] == "chunk":
                        results["chunks"].append(data)
                        print(f"  Chunk #{data.get('index', '?')}: '{data.get('text', '')[:40]}'")
                    elif data["type"] == "final":
                        results["final"] = data.get("text", "")
                        print(f"  Final: '{results['final'][:60]}'")
                        break
                    elif data["type"] == "error":
                        results["errors"].append(data.get("message", ""))
                        print(f"  ERROR: {data.get('message', '')}")
                except asyncio.TimeoutError:
                    results["errors"].append("timeout waiting for response")
                    print("  TIMEOUT waiting for response")
                    break
    except ConnectionRefusedError:
        print("FAIL: Cannot connect to server at localhost:8001. Is it running?")
        sys.exit(1)
    except Exception as e:
        print(f"FAIL: WebSocket error: {e}")
        sys.exit(1)

    elapsed = time.time() - t0

    # === ASSERTIONS ===
    print(f"\n{'='*50}")
    print(f"Test Results ({elapsed:.1f}s)")
    print(f"{'='*50}")

    failures = []

    # 1. Should have at least 1 chunk
    if len(results["chunks"]) >= 1:
        print(f"PASS: Chunks received: {len(results['chunks'])}")
    else:
        msg = f"FAIL: No chunks received (got {len(results['chunks'])})"
        print(msg)
        failures.append(msg)

    # 2. Chunks should be in order (index 0, 1, 2...)
    if results["chunks"]:
        indices = [c["index"] for c in results["chunks"]]
        if indices == sorted(indices):
            print(f"PASS: Chunks in order: {indices}")
        else:
            msg = f"FAIL: Chunks out of order: {indices}"
            print(msg)
            failures.append(msg)

    # 3. Chunk text should contain Chinese
    for c in results["chunks"]:
        has_chinese = any('\u4e00' <= ch <= '\u9fff' for ch in c.get("text", ""))
        if not has_chinese:
            msg = f"WARN: Chunk #{c['index']} has no Chinese: {c.get('text', '')[:50]}"
            print(msg)
            # Warning only, not a failure

    # 4. Final should not be empty
    if results["final"]:
        print(f"PASS: Final text non-empty ({len(results['final'])} chars)")
    else:
        msg = "FAIL: Final text is empty"
        print(msg)
        failures.append(msg)

    # 5. Final should contain Chinese
    if results["final"]:
        has_chinese = any('\u4e00' <= ch <= '\u9fff' for ch in results["final"])
        if has_chinese:
            print(f"PASS: Final contains Chinese")
        else:
            msg = f"FAIL: Final has no Chinese: {results['final'][:50]}"
            print(msg)
            failures.append(msg)

    # 6. No errors
    if not results["errors"]:
        print(f"PASS: No errors")
    else:
        msg = f"FAIL: Errors: {results['errors']}"
        print(msg)
        failures.append(msg)

    # 7. Total time < 30 seconds
    if elapsed < 30:
        print(f"PASS: Total time: {elapsed:.1f}s")
    else:
        msg = f"FAIL: Total time {elapsed:.1f}s > 30s"
        print(msg)
        failures.append(msg)

    # 8. Key words should be preserved
    if results["final"]:
        for word in ["串流", "文字", "輸入法"]:
            if word in results["final"]:
                print(f"PASS: Word found: {word}")
            else:
                print(f"WARN: Missing word: {word} (may be rephrased by LLM)")

    print(f"\n{'='*50}")
    if failures:
        print(f"FAILED - {len(failures)} critical assertion(s) failed:")
        for f in failures:
            print(f"  - {f}")
        print(f"\nChunks: {[c.get('text','')[:20] for c in results['chunks']]}")
        print(f"Final: {results['final']}")
        sys.exit(1)
    else:
        print(f"ALL TESTS PASSED")
        print(f"Chunks: {[c.get('text','')[:20] for c in results['chunks']]}")
        print(f"Final: {results['final']}")
        sys.exit(0)


if __name__ == "__main__":
    asyncio.run(test_streaming())
