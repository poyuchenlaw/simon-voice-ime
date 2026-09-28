#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK="${JAVA_HOME:-/home/simon/.local/jdk/jdk-17.0.2}"
HOST="$ROOT/evidence/rime_spike/host-install"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes/com/simon/voiceime" "$TMP/user"
cat > "$TMP/classes/com/simon/voiceime/T9RimeEngine.java" <<'JAVA'
package com.simon.voiceime;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public final class T9RimeEngine {
  static { System.load(System.getProperty("probe.library")); }
  static native long nativeCreate(String shared,String user);
  static native void nativeDestroy(long h);
  static native byte[] nativeQuery(String shared,String user,String keys);
  static native byte[] nativeRoundTrip(String value);

  public static void main(String[] a) throws Exception {
    String rare="𠮷㐂";
    if(!rare.equals(new String(nativeRoundTrip(rare),StandardCharsets.UTF_8)))throw new AssertionError("JNI UTF-8 byte array corrupted supplementary/rare Han");
    long h=nativeCreate(a[0],a[1]); if(h==0)throw new AssertionError("Rime initialization failed");
    try {
      String first=new String(nativeQuery(a[0],a[1],"18"),StandardCharsets.UTF_8);
      String second=new String(nativeQuery(a[0],a[1],"28"),StandardCharsets.UTF_8);
      if(first.length()<4||second.length()<4||first.equals("[]")||second.equals("[]"))throw new AssertionError("prebuilt Rime segment lookup returned no candidates");
      if(first.chars().allMatch(c->c<128))throw new AssertionError("Rime output was not UTF-8 Chinese text");
      System.out.println("PASS JNI UTF-8 byte[] roundtrip (𠮷㐂); Rime prebuilt candidate queries 18 and 28; segment-level key/candidate separation");
    } finally {nativeDestroy(h);}
  }
}
JAVA
"$JDK/bin/javac" -encoding UTF-8 -d "$TMP/classes" "$TMP/classes/com/simon/voiceime/T9RimeEngine.java"
g++ -std=c++17 -O2 -fPIC -shared \
  -I"$JDK/include" -I"$JDK/include/linux" -I"$ROOT/evidence/rime_spike/android-prefix/include" \
  "$ROOT/app/src/phone/cpp/rime_jni.cpp" -L"$HOST/lib" -lrime \
  -Wl,-rpath,"$HOST/lib" -o "$TMP/librime_jni_probe.so"
java -Dprobe.library="$TMP/librime_jni_probe.so" -cp "$TMP/classes" \
  com.simon.voiceime.T9RimeEngine "$ROOT/app/src/phone/assets/rime" "$TMP/user"
