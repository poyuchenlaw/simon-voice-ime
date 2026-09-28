package com.simon.voiceime;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Traditional Zhuyin page backed by the same packaged librime runtime as T9. */
final class RimeZhuyinEngine implements ZhuyinInputController.Engine, AutoCloseable {
    private final RimeZhuyinNative nativeEngine;

    RimeZhuyinEngine(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File files = app.getFilesDir();
        File shared = new File(files, "rime/shared");
        File user = new File(files, "rime/user");
        File marker = new File(files, "rime/.asset-version");
        String packaged = T9RimeEngine.assetVersion(app);
        String installed = marker.isFile()
                ? new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8) : null;
        if (T9AssetVersion.shouldCopy(installed, packaged) || !shared.isDirectory()) {
            T9RimeEngine.copyTree(app, "rime", shared, true);
            marker.getParentFile().mkdirs();
            java.nio.file.Files.write(marker.toPath(), packaged.getBytes(StandardCharsets.UTF_8));
        }
        if (!user.isDirectory() && !user.mkdirs()) throw new IllegalStateException("Cannot create Rime user directory");
        nativeEngine = new RimeZhuyinNative(shared.getAbsolutePath(), user.getAbsolutePath());
    }

    @Override public void key(String symbol) {
        int key = ZhuyinKeyMap.physicalKey(symbol);
        if (key >= 0) nativeEngine.key(key);
    }
    @Override public void backspace() { nativeEngine.key(8); }
    @Override public void space() { nativeEngine.key(32); }
    @Override public void enter() { nativeEngine.key(13); }
    @Override public void choose(int index) { nativeEngine.choose(index); }
    @Override public void moveCursor(String direction) {
        if ("left".equals(direction)) nativeEngine.moveCursor(false);
        else if ("right".equals(direction)) nativeEngine.moveCursor(true);
    }
    @Override public int cursorPosition() { return nativeEngine.cursor(); }
    @Override public String composingText() { return nativeEngine.composing(); }
    @Override public List<String> candidates() {
        String[] values = nativeEngine.candidates();
        List<String> out = new ArrayList<>();
        for (String text : values) {
            if (!text.isEmpty()) out.add(text);
        }
        return out;
    }
    @Override public String takeCommit() { return nativeEngine.takeCommit(); }
    @Override public void clear() { nativeEngine.clear(); }
    @Override public void close() { nativeEngine.close(); }
}
