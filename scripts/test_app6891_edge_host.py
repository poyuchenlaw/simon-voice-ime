#!/usr/bin/env python3
"""Run the production left-edge check with layout/asset boundary fixtures, not Android simulation."""
import argparse
import pathlib
import re
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--out', required=True)
p.add_argument('--red', action='store_true')
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
out = pathlib.Path(a.out).resolve()
out.mkdir(parents=True, exist_ok=True)
source = (root / 'app/src/phone/java/com/simon/voiceime/DualHandKeyboard.java').read_text()
signature = 'private boolean leftEdge('
if signature not in source:
    assert a.red, 'Production leftEdge method missing'
    method = 'private boolean leftEdge(String key) { return false; }'
else:
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 0
    for match in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{}]', source[opening:], re.S):
        if match.group() == '{': depth += 1
        elif match.group() == '}':
            depth -= 1
            if depth == 0:
                method = source[start:opening + match.end()]
                break
    else: raise AssertionError('Unclosed production method')
java = '''package com.simon.voiceime;
import java.util.*; import java.io.*; import org.junit.Test; import static org.junit.Assert.*;
public class EdgeLayoutTest {
    final Map<String,FrameLayout> keys = new LinkedHashMap<>(); DualHandTable table;
    static final class ViewGroup {
        final int padding; ViewGroup(int padding) { this.padding=padding; }
        int getPaddingLeft() { return padding; }
        static final class MarginLayoutParams { int leftMargin=2; }
    }
    static final class FrameLayout {
        final int left; final ViewGroup parent; final ViewGroup.MarginLayoutParams params=new ViewGroup.MarginLayoutParams();
        FrameLayout(int left, ViewGroup parent) { this.left=left; this.parent=parent; }
        int getLeft() { return left; } ViewGroup getParent() { return parent; }
        ViewGroup.MarginLayoutParams getLayoutParams() { return params; }
    }
    public EdgeLayoutTest() throws Exception {
        java.nio.file.Path assets=java.nio.file.Path.of(System.getProperty("edge.assets"));
        table=new DualHandTable(java.nio.file.Files.newBufferedReader(assets.resolve("dual_hand_map.tsv")),
            java.nio.file.Files.newBufferedReader(assets.resolve("left_hand.tsv")),
            java.nio.file.Files.newBufferedReader(assets.resolve("draft_chars.tsv")));
    }
    /* METHOD */
    void row(String... names) { ViewGroup row=new ViewGroup(3); for(int i=0;i<names.length;i++) keys.put(names[i],new FrameLayout(5+i*80,row)); }
    @Test public void portraitOnlyFirstLeftColumnEnabled() {
        row("1","2"); row("3","4"); row("5","6"); row("A","B"); row("C","D"); row("E","F");
        row("tools","symbols","Ø","clipboard","core");
        Set<String> enabled=new LinkedHashSet<>(); for(String key:keys.keySet()) if(leftEdge(key)) enabled.add(key);
        assertEquals(new LinkedHashSet<>(Arrays.asList("1","3","5")),enabled);
    }
    @Test public void splitUsesActualThreeColumnRowsAndExcludesBottom() {
        row("1","2","3"); row("4","5","6"); row("tools","symbols");
        row("A","B","C"); row("D","E","F"); row("Ø","clipboard","core");
        Set<String> enabled=new LinkedHashSet<>(); for(String key:keys.keySet()) if(leftEdge(key)) enabled.add(key);
        assertEquals(new LinkedHashSet<>(Arrays.asList("1","4")),enabled);
    }
    @Test public void emptyLeftMappingDoesNotEnableShortSwipe() throws Exception {
        row("1","2");
        java.nio.file.Path assets=java.nio.file.Path.of(System.getProperty("edge.assets"));
        String left=java.nio.file.Files.readString(assets.resolve("left_hand.tsv")).replace("1\\tleft\\tㄇ", "1\\tleft\\t");
        table=new DualHandTable(java.nio.file.Files.newBufferedReader(assets.resolve("dual_hand_map.tsv")),new StringReader(left),
            java.nio.file.Files.newBufferedReader(assets.resolve("draft_chars.tsv")));
        assertFalse(leftEdge("1")); assertFalse(leftEdge("missing"));
    }
}
'''.replace('/* METHOD */', method)
(out / 'EdgeLayoutTest.java').write_text(java)
(out / 'leftEdge.extracted.java').write_text(method + '\n')
cp = ':'.join(str(root / 'out/test-libs' / n) for n in ['junit-4.13.2.jar', 'hamcrest-core-1.3.jar'])
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', cp, '-d', str(out), str(out/'EdgeLayoutTest.java'),
    str(root/'app/src/main/java/com/simon/voiceime/DualHandTable.java')], check=True)
raise SystemExit(subprocess.run(['java', '-ea', '-Dedge.assets='+str(root/'app/src/phone/assets/dual_hand'),
    '-cp', str(out)+':'+cp, 'org.junit.runner.JUnitCore', 'com.simon.voiceime.EdgeLayoutTest']).returncode)
