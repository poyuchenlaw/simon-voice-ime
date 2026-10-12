#!/usr/bin/env python3
"""Addendum 2 replaces the impossible pairwise contrast rule with actual palette tests."""
import pathlib, subprocess
w=pathlib.Path(__file__).resolve().parents[1];out=w/'out/app690a-palette';out.mkdir(parents=True,exist_ok=True)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(w/'app/src/main/java/com/simon/voiceime/DualHandVisuals.java'),str(w/'scripts/app690a_host/App690APaletteTest.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690APaletteTest']).returncode)
