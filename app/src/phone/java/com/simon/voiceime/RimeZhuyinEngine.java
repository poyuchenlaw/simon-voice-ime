package com.simon.voiceime;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Traditional Zhuyin page backed by its packaged librime runtime. */
final class RimeZhuyinEngine implements ZhuyinInputController.Engine, AutoCloseable {
    private final RimeZhuyinNative nativeEngine;

    RimeZhuyinEngine(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File files = app.getFilesDir();
        File shared = new File(files, "rime/shared");
        File user = new File(files, "rime/user");
        File marker = new File(files, "rime/.asset-version");
        String packaged = Long.toString(app.getPackageManager()
                .getPackageInfo(app.getPackageName(), 0).lastUpdateTime);
        String installed = marker.isFile()
                ? new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8) : null;
        if (!packaged.equals(installed) || !shared.isDirectory()) {
            copyAssets(app, "rime", shared);
            marker.getParentFile().mkdirs();
            java.nio.file.Files.write(marker.toPath(), packaged.getBytes(StandardCharsets.UTF_8));
        }
        if (!user.isDirectory() && !user.mkdirs()) throw new IllegalStateException("Cannot create Rime user directory");
        nativeEngine = new RimeZhuyinNative(shared.getAbsolutePath(), user.getAbsolutePath());
    }

    @Override public boolean preservesUnparsedInput() { return true; }

    @Override public void key(String symbol) {
        int key = ZhuyinKeyMap.physicalKey(symbol);
        if (key >= 0) nativeEngine.key(key);
    }
    @Override public void backspace() { nativeEngine.backspace(); }
    @Override public void space() { nativeEngine.space(); }
    @Override public void enter() { nativeEngine.enter(); }
    @Override public void choose(int index) { nativeEngine.choose(index); }
    @Override public void moveCursor(String direction) {
        if ("left".equals(direction)) nativeEngine.moveCursor(false);
        else if ("right".equals(direction)) nativeEngine.moveCursor(true);
    }
    @Override public boolean moveCursorToPreviewCharacter(int codePointIndex) {
        return nativeEngine.moveCursorToPreviewCharacter(codePointIndex);
    }
    @Override public void moveCursorToEnd() { nativeEngine.moveCursorToEnd(); }
    @Override public int[] previewSelectionRange() { return nativeEngine.previewSelectionRange(); }
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

    private static void copyAssets(Context context, String assetPath, File destination) throws Exception {
        String[] children = context.getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            destination.getParentFile().mkdirs();
            try (java.io.InputStream in = context.getAssets().open(assetPath);
                 java.io.OutputStream out = new java.io.FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = in.read(buffer)) >= 0;) out.write(buffer, 0, count);
            }
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs())
            throw new IllegalStateException("Cannot create Rime asset directory");
        for (String child : children) copyAssets(context, assetPath + "/" + child, new File(destination, child));
    }
}
