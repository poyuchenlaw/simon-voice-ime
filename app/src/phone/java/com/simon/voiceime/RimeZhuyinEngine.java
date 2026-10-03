package com.simon.voiceime;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Ordered uncommitted segments. Each punctuation is a literal boundary;
 * independent native sessions keep conversion and caret edits on their own side. */
final class RimeZhuyinEngine implements ZhuyinInputController.Engine, AutoCloseable {
    private static final class Part {
        SingleRimeZhuyinEngine engine;String literal;
        Part(SingleRimeZhuyinEngine e){engine=e;literal="";}
        Part(String text){literal=text;}
        String keys(){return engine==null?literal:engine.sentenceKeys();}
        String text(){return engine==null?literal:engine.previewText();}
    }
    private final List<Part> parts=new ArrayList<>();
    private int active=0,caret=-1;
    private String pending="";
    private final String shared,user;
    private final List<int[]> choices=new ArrayList<>();
    RimeZhuyinEngine(String shared,String user){this(new SingleRimeZhuyinEngine(shared,user));}
    RimeZhuyinEngine(Context c)throws Exception{this(new SingleRimeZhuyinEngine(c));}
    private RimeZhuyinEngine(SingleRimeZhuyinEngine e){shared=e.sharedPath;user=e.userPath;parts.add(new Part(e));}
    private SingleRimeZhuyinEngine current(){return parts.get(active).engine;}
    private SingleRimeZhuyinEngine fresh(){return new SingleRimeZhuyinEngine(shared,user);}
    private static int count(String s){return s.codePointCount(0,s.length());}
    private int keyOffset(){int n=0;for(int i=0;i<active;i++)n+=count(parts.get(i).keys());return n;}
    private int textOffset(){int n=0;for(int i=0;i<active;i++)n+=count(parts.get(i).text());return n;}
    private String join(boolean keys){StringBuilder b=new StringBuilder();for(Part p:parts)b.append(keys?p.keys():p.text());return b.toString();}
    @Override public int rowWordLimit(){return current().rowWordLimit();}
    @Override public String[] localRepair(){
        String[] fix=current().nativeEngine.localRepair();if(fix.length!=2)return fix;
        StringBuilder keys=new StringBuilder(),text=new StringBuilder();
        for(int i=0;i<parts.size();i++){Part p=parts.get(i);keys.append(i==active?fix[0].replace("ˉ"," "):p.keys());text.append(i==active?fix[1]:p.text());}
        return new String[]{keys.toString(),text.toString()};
    }
    @Override public String sentenceKeys(){return join(true);}
    @Override public String previewText(){return join(false);}
    @Override public String composingText(){return parts.size()==1?current().composingText():previewText();}
    @Override public String phoneticText(){return join(true).replace(" ","ˉ");}
    @Override public List<String> phoneticSyllables(){List<String> out=new ArrayList<>();for(Part p:parts)if(p.engine==null)out.add(p.literal);else out.addAll(p.engine.phoneticSyllables());return out;}
    @Override public boolean commitsAreRendered(){return true;}
    @Override public boolean preservesUnparsedInput(){return true;}
    @Override public int cursorPosition(){return keyOffset()+current().cursorPosition();}
    @Override public int keyPreviewCaret(){int p=current().keyPreviewCaret();return p<0?-1:textOffset()+p;}
    @Override public boolean keyCaret(int at){
        if(at<0||at>count(sentenceKeys()))return false;
        for(Part p:parts)if(p.engine!=null)p.engine.moveCursorToEnd();
        int offset=0;
        for(int i=0;i<parts.size();i++) {Part p=parts.get(i);int n=count(p.keys());
            if(p.engine!=null&&at>=offset&&at<=offset+n){active=i;caret=at;return p.engine.keyCaret(at-offset);}
            offset+=n;
        }
        return false;
    }
    @Override public boolean focusAtKey(int at){
        if(at==0)return false;
        String keys=sentenceKeys();if(at>0&&!" ".equals(keys.substring(at-1,at))&&ZhuyinKeyMap.physicalKey(keys.substring(at-1,at))<0)return false;
        return current().focusAtKey(at-keyOffset());
    }
    @Override public boolean focusAfterKeyEdit(int at){return current().focusAfterKeyEdit(at-keyOffset());}
    @Override public void moveCursorToEnd(){for(Part p:parts)if(p.engine!=null)p.engine.moveCursorToEnd();active=parts.size()-1;caret=-1;}
    @Override public void moveCursor(String direction){int at=caret<0?cursorPosition():caret;keyCaret(Math.max(0,Math.min(count(sentenceKeys()),at+("left".equals(direction)?-1:1))));}
    @Override public void key(String symbol){current().key(symbol);if(caret>=0)caret=cursorPosition();}
    @Override public void recordTouch(int[] k,double[] p,boolean[] a){current().recordTouch(k,p,a);}
    @Override public void backspace(){
        if(current().cursorPosition()==0&&active>0&&parts.get(active-1).engine==null){
            parts.remove(active-1);active--;caret=keyOffset();current().keyCaret(0);return;
        }
        current().backspace();if(caret>=0)caret=cursorPosition();
    }
    @Override public void space(){current().space();}
    @Override public void enter(){
        // The visible conversion is the authoritative Enter payload, including
        // pinned local choices and literal boundaries. No second translation.
        String text=previewText();clear();pending=text;
    }
    @Override public void choose(int index){current().choose(index);}
    @Override public List<String> candidates(){
        if(parts.size()==1)return current().candidates();
        List<String> out=new ArrayList<>();choices.clear();
        // Latest segment first; shorter prefix candidates remain selectable
        // and carry an explicit native source span for partial commits.
        for(int i=parts.size()-1;i>=0;i--){Part p=parts.get(i);if(p.engine==null)continue;
            List<String> menu=p.engine.candidates();for(int j=0;j<menu.size();j++)if(!out.contains(menu.get(j))){out.add(menu.get(j));choices.add(new int[]{i,j});}
        }
        return out;
    }
    @Override public void chooseAndCommit(int index){
        if(parts.size()==1){current().chooseAndCommit(index);return;}
        candidates();if(index<0||index>=choices.size())return;int[] selected=choices.get(index);active=selected[0];
        current().chooseAndCommit(selected[1]);String value=current().takeCommit();if(value.isEmpty())return;
        StringBuilder prefix=new StringBuilder();for(int i=0;i<active;i++)prefix.append(parts.get(i).text());prefix.append(value);
        int remove=active;
        if(current().sentenceKeys().isEmpty()){
            remove=active+1;
            while(remove<parts.size()&&parts.get(remove).engine==null)prefix.append(parts.get(remove++).literal);
        }
        for(int i=0;i<remove;i++){Part p=parts.remove(0);if(p.engine!=null)p.engine.close();}
        if(parts.isEmpty())parts.add(new Part(fresh()));pending+=prefix;moveCursorToEnd();
    }
    @Override public String takeCommit(){String s=pending;pending="";String own=current().takeCommit();
        if(!own.isEmpty()){StringBuilder prefix=new StringBuilder();for(int i=0;i<active;i++)prefix.append(parts.get(i).text());
            for(int i=0;i<active;i++){Part p=parts.get(0);if(p.engine!=null)p.engine.close();parts.remove(0);}active=0;s+=prefix.toString()+own;
        }return s;
    }
    @Override public List<String> optionKinds(){return current().optionKinds();}
    @Override public List<String> optionGroups(){return current().optionGroups();}
    @Override public List<String> regroupLabels(){return current().regroupLabels();}
    String[] regroupReadings(){return current().regroupReadings();}
    private int[] offset(int[] r){if(r==null)return null;return new int[]{r[0]+textOffset(),r[1]+textOffset()};}
    @Override public int[] previewEditRange(){return offset(current().previewEditRange());}
    @Override public int[] previewSelectionRange(){return offset(current().previewSelectionRange());}
    @Override public boolean previewLiteral(int target){int offset=0;for(Part p:parts){int n=count(p.text());if(target>=offset&&target<offset+n)return p.engine==null;offset+=n;}return false;}
    @Override public boolean moveCursorToPreviewCharacter(int target){
        int offset=0;for(int i=0;i<parts.size();i++){Part p=parts.get(i);int n=count(p.text());
            if(target>=offset&&target<offset+n){
                if(p.engine==null)return keyCaret(keyLengthBefore(i)+count(p.literal));
                active=i;caret=-1;return p.engine.moveCursorToPreviewCharacter(target-offset);
            }offset+=n;
        }return false;
    }
    private int keyLengthBefore(int at){int n=0;for(int i=0;i<at;i++)n+=count(parts.get(i).keys());return n;}
    @Override public boolean regroup(int boundary){
        int offset=0;
        for(int i=0;i<parts.size();i++){
            Part p=parts.get(i);int length=count(p.text());
            if(p.engine!=null&&boundary>=offset&&boundary<=offset+length){active=i;caret=-1;return current().regroup(boundary-offset);}
            offset+=length;
        }
        return false;
    }
    @Override public boolean chooseRegroup(int index){boolean ok=current().chooseRegroup(index);if(ok)moveCursorToEnd();return ok;}
    @Override public boolean prepareSentence(String keys,String text){
        List<Part> mapped=new ArrayList<>();int keyStart=0,textStart=0;
        try{
            for(int i=0;i<keys.length();i++)if(keys.charAt(i)!=' '&&ZhuyinKeyMap.physicalKey(keys.substring(i,i+1))<0){
                String mark=keys.substring(i,i+1);int at=text.indexOf(mark,textStart);if(at<0)throw new IllegalArgumentException("literal boundary");
                SingleRimeZhuyinEngine e=fresh();mapped.add(new Part(e));
                String k=keys.substring(keyStart,i),t=text.substring(textStart,at);
                if(!k.isEmpty()&&!e.prepareSentence(k,t)||k.isEmpty()&&!t.isEmpty())throw new IllegalArgumentException("prefix mapping");
                mapped.add(new Part(mark));keyStart=i+1;textStart=at+1;
            }
            SingleRimeZhuyinEngine e=fresh();mapped.add(new Part(e));String k=keys.substring(keyStart),t=text.substring(textStart);
            if(!k.isEmpty()&&!e.prepareSentence(k,t)||k.isEmpty()&&!t.isEmpty())throw new IllegalArgumentException("clause mapping");
        }catch(Exception invalid){for(Part p:mapped)if(p.engine!=null)p.engine.close();return false;}
        for(Part p:parts)if(p.engine!=null)p.engine.close();parts.clear();parts.addAll(mapped);active=parts.size()-1;caret=-1;return previewText().equals(text);
    }
    @Override public void learnPhrase(String w,String p){current().learnPhrase(w,p);}
    @Override public List<String[]> personalPhrases(){return current().personalPhrases();}
    @Override public boolean punctuation(String text){
        SingleRimeZhuyinEngine old=current();String keys=old.sentenceKeys();int at=caret<0?keys.length():caret-keyOffset();
        List<String> reading=old.phoneticSyllables();String shown=old.previewText();int stop=0,split=-1;
        for(int i=0;i<=reading.size();i++){if(stop==at){split=i;break;}if(i<reading.size())stop+=reading.get(i).length();}
        SingleRimeZhuyinEngine left=fresh(),right=fresh();
        if(split>=0&&reading.size()==count(shown)){
            left.nativeEngine.restore(reading.subList(0,split),shown.substring(0,shown.offsetByCodePoints(0,split)));
            right.nativeEngine.restore(reading.subList(split,reading.size()),shown.substring(shown.offsetByCodePoints(0,split)));
        }else{
            for(int i=0;i<at;i++)left.key(keys.substring(i,i+1));
            for(int i=at;i<keys.length();i++)right.key(keys.substring(i,i+1));
        }
        left.nativeEngine.copyTouches(old.nativeEngine,0,at);right.nativeEngine.copyTouches(old.nativeEngine,at,keys.length());
        parts.remove(active);old.close();parts.add(active,new Part(left));parts.add(active+1,new Part(text));parts.add(active+2,new Part(right));active+=2;
        caret=keyOffset();current().keyCaret(0);return true;
    }
    @Override public void clear(){for(Part p:parts)if(p.engine!=null)p.engine.clear();
        SingleRimeZhuyinEngine kept=current();for(Part p:parts)if(p.engine!=null&&p.engine!=kept)p.engine.close();
        parts.clear();parts.add(new Part(kept));active=0;caret=-1;pending="";choices.clear();}
    @Override public void close(){for(Part p:parts)if(p.engine!=null)p.engine.close();parts.clear();}
}

/** Traditional Zhuyin page backed by its packaged librime runtime. */
final class SingleRimeZhuyinEngine implements ZhuyinInputController.Engine, AutoCloseable {
    final RimeZhuyinNative nativeEngine;
    String sharedPath,userPath;

    SingleRimeZhuyinEngine(String shared,String user) { sharedPath=shared;userPath=user;nativeEngine=new RimeZhuyinNative(shared,user); }

    SingleRimeZhuyinEngine(Context context) throws Exception {
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
        sharedPath=shared.getAbsolutePath();userPath=user.getAbsolutePath();
        nativeEngine = new RimeZhuyinNative(sharedPath,userPath);
    }

    @Override public void learnPhrase(String word,String pronunciation){
        try{RimeVocabularyInstaller.remember(new File(userPath),word,pronunciation);}
        catch(java.io.IOException failure){throw new IllegalStateException("personal vocabulary persistence",failure);}
    }
    @Override public String sentenceKeys(){return nativeEngine.sentenceKeys();}
    @Override public boolean prepareSentence(String keys,String text){
        StringBuilder raw=new StringBuilder();for(int i=0;i<keys.length();i++){
            int key=keys.charAt(i)==' '?32:ZhuyinKeyMap.physicalKey(keys.substring(i,i+1));
            if(key<0)return false;raw.append((char)key);
        }
        return nativeEngine.prepareSentence(raw.toString(),text);
    }
    @Override public void recordTouch(int[] keys,double[] probabilities,boolean[] adjacent){nativeEngine.recordTouch(keys,probabilities,adjacent);}
    String[] regroupReadings(){return nativeEngine.regroupReadings();}
    @Override public boolean regroup(int boundary) { return nativeEngine.regroup(boundary); }
    @Override public boolean chooseRegroup(int index) { return nativeEngine.chooseRegroup(index); }
    @Override public List<String> optionGroups(){return java.util.Arrays.asList(nativeEngine.optionGroups());}
    @Override public List<String> optionKinds(){return java.util.Arrays.asList(nativeEngine.optionKinds());}
    @Override public List<String> regroupLabels() { return java.util.Arrays.asList(nativeEngine.regroupLabels()); }
    @Override public String previewText() { return nativeEngine.preview(); }
    @Override public int[] previewEditRange() { return nativeEngine.editRange(); }
    @Override public List<String> phoneticSyllables() { return java.util.Arrays.asList(nativeEngine.readingSyllables()); }
    @Override public String phoneticText() { return nativeEngine.reading(); }
    @Override public boolean preservesUnparsedInput() { return true; }

    @Override public void key(String symbol) {
        int key = ZhuyinKeyMap.physicalKey(symbol);
        if (key >= 0) nativeEngine.key(key);
    }
    @Override public boolean focusAtKey(int at){return nativeEngine.focusAtKey(at);}
    @Override public boolean focusAfterKeyEdit(int at){return nativeEngine.focusAfterKeyEdit(at);}
    @Override public boolean keyCaret(int at){return nativeEngine.keyCaret(at);}
    @Override public int keyPreviewCaret(){return nativeEngine.keyPreviewCaret();}
    @Override public void backspace() { nativeEngine.backspace(); }
    @Override public void space() { nativeEngine.space(); }
    @Override public void enter() { nativeEngine.enter(); }
    @Override public void chooseAndCommit(int index) { nativeEngine.chooseAndCommit(index); }
    @Override public void choose(int index) { nativeEngine.choose(index); }
    @Override public void moveCursor(String direction) {
        if ("left".equals(direction)) nativeEngine.moveCursor(false);
        else if ("right".equals(direction)) nativeEngine.moveCursor(true);
    }
    @Override public boolean moveCursorToPreviewCharacter(int codePointIndex) {
        return nativeEngine.focusCharacter(codePointIndex);
    }
    @Override public void moveCursorToEnd() { nativeEngine.moveCursorToEnd(); }
    @Override public int[] previewSelectionRange() { return nativeEngine.previewSelectionRange(); }
    @Override public int cursorPosition() { return nativeEngine.cursor(); }
    @Override public String composingText() { return nativeEngine.composing(); }
    @Override public int rowWordLimit(){return nativeEngine.rowWordLimit();}
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
