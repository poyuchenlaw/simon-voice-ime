package com.simon.voiceime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Thin UI-facing adapter. The conversion, candidate ranking and learning remain in libchewing. */
final class ZhuyinInputController {
    interface Engine {
        void key(String key);
        void backspace();
        void space();
        void enter();
        void choose(int index);
        void moveCursor(String direction);
        int cursorPosition();
        String composingText();
        List<String> candidates();
        String takeCommit();
        void clear();
        default void learnPhrase(String word, String pronunciation) { }
        default List<String[]> personalPhrases() { return Collections.emptyList(); }
    }

    static final class State {
        final String composingText;
        final List<String> candidates;
        final String commitText;
        final boolean accepted;
        final int cursorPosition;
        final String candidateKind;
        final List<ZhuyinWordIndex.Entry> abbreviationEntries;
        State(String composingText, List<String> candidates, String commitText, boolean accepted, int cursorPosition) {
            this(composingText, candidates, commitText, accepted, cursorPosition, "engine", Collections.emptyList());
        }
        State(String composingText, List<String> candidates, String commitText, boolean accepted, int cursorPosition, String candidateKind, List<ZhuyinWordIndex.Entry> abbreviationEntries) {
            this.composingText = composingText;
            this.candidates = candidates;
            this.commitText = commitText;
            this.accepted = accepted;
            this.cursorPosition = cursorPosition;
            this.candidateKind = candidateKind;
            this.abbreviationEntries = abbreviationEntries;
        }
    }

    private final Engine engine;
    private final ZhuyinWordIndex abbreviationIndex;
    private final StringBuilder abbreviationKeys = new StringBuilder();
    private List<ZhuyinWordIndex.Entry> abbreviationEntries = Collections.emptyList();
    private List<MixedChoice> mixedChoices = Collections.emptyList();
    private List<String> associationCandidates = Collections.emptyList();
    ZhuyinInputController(Engine engine) { this.engine = engine == null ? DisabledEngine.INSTANCE : engine; this.abbreviationIndex = null; }
    ZhuyinInputController(Engine engine, ZhuyinWordIndex index) {
        this.engine = engine == null ? DisabledEngine.INSTANCE : engine;
        this.abbreviationIndex = index;
        if (index != null) {
            List<String[]> learned = this.engine.personalPhrases();
            index.rememberPersonal(learned);
        }
    }

    State press(String key) {
        if ("backspace".equals(key) && abbreviationKeys.length() > 0) {
            engine.backspace();
            abbreviationKeys.setLength(abbreviationKeys.length() - 1);
            abbreviationEntries = abbreviationKeys.length() >= 2 ? abbreviationIndex.lookup(abbreviationKeys.toString()) : Collections.emptyList();
            if (abbreviationKeys.length() == 0) { mixedChoices=Collections.emptyList(); return snapshot(true, true); }
            return mixedSnapshot(true);
        }
        if (isZhuyin(key) && abbreviationIndex != null
                && (abbreviationKeys.length() > 0 || engine.composingText().isEmpty())) {
            if (abbreviationKeys.length() > 0) {
                String extended = abbreviationKeys.toString() + key;
                if (abbreviationIndex.hasPrefix(extended)) {
                    abbreviationKeys.append(key);
                    engine.key(key);
                    abbreviationEntries = abbreviationKeys.length() >= 2 ? abbreviationIndex.lookup(extended) : Collections.emptyList();
                    return mixedSnapshot(true);
                }
                abbreviationKeys.setLength(0); abbreviationEntries=Collections.emptyList(); mixedChoices=Collections.emptyList();
            } else if (abbreviationIndex.hasPrefix(key)) {
                abbreviationKeys.append(key);
                engine.key(key);
                return mixedSnapshot(true);
            }
        }
        if (abbreviationKeys.length() > 0) { abbreviationKeys.setLength(0); abbreviationEntries=Collections.emptyList(); mixedChoices=Collections.emptyList(); }
        associationCandidates = Collections.emptyList();
        if ("space".equals(key)) engine.space();
        else if ("enter".equals(key)) engine.enter();
        else if ("backspace".equals(key)) engine.backspace();
        else if (isZhuyin(key)) engine.key(key);
        else return snapshot(false, false);
        return snapshot(true, true);
    }

    State chooseCandidate(int index) {
        if (!mixedChoices.isEmpty()) {
            if(index<0||index>=mixedChoices.size())return mixedSnapshot(false);
            MixedChoice selected=mixedChoices.get(index);mixedChoices=Collections.emptyList();
            if(selected.entry!=null){engine.learnPhrase(selected.entry.word,selected.entry.pronunciation);abbreviationIndex.rememberPersonal(selected.entry);engine.clear();abbreviationKeys.setLength(0);abbreviationEntries=Collections.emptyList();return new State("",Collections.emptyList(),selected.entry.word,true,0,"abbreviation",Collections.emptyList());}
            engine.choose(selected.engineIndex);abbreviationKeys.setLength(0);abbreviationEntries=Collections.emptyList();return snapshot(true,true);
        }
        if (abbreviationKeys.length() >= 2) {
            if (index < 0 || index >= abbreviationEntries.size()) return abbreviationSnapshot();
            ZhuyinWordIndex.Entry selected = abbreviationEntries.get(index);
            engine.learnPhrase(selected.word, selected.pronunciation);
            abbreviationIndex.rememberPersonal(selected);
            abbreviationKeys.setLength(0); abbreviationEntries = Collections.emptyList();
            return new State("", Collections.emptyList(), selected.word, true, 0, "abbreviation", Collections.emptyList());
        }
        List<String> candidates = engine.candidates();
        if (index < 0 || index >= candidates.size()) return snapshot(false, false);
        engine.choose(index);
        return snapshot(true, true);
    }
    String candidateOrigin(int index){
        if(!mixedChoices.isEmpty()&&index>=0&&index<mixedChoices.size())return mixedChoices.get(index).entry==null?"engine":"abbreviation";
        return abbreviationKeys.length()>=2?"abbreviation":"engine";
    }

    State moveCursorLeft() { mixedChoices=Collections.emptyList(); engine.moveCursor("left"); return snapshot(true, true); }
    State moveCursorRight() { mixedChoices=Collections.emptyList(); engine.moveCursor("right"); return snapshot(true, true); }

    State clear() { abbreviationKeys.setLength(0); abbreviationEntries = Collections.emptyList(); mixedChoices=Collections.emptyList(); associationCandidates = Collections.emptyList(); engine.clear(); return snapshot(true, true); }
    State state() { return snapshot(false, false); }
    State showAssociations(List<String> candidates) {
        associationCandidates = candidates == null ? Collections.emptyList() : new ArrayList<>(candidates);
        return associationSnapshot();
    }
    State chooseAssociation(int index) {
        if (index < 0 || index >= associationCandidates.size()) return associationSnapshot();
        String value = associationCandidates.get(index);
        associationCandidates = Collections.emptyList();
        return new State("", Collections.emptyList(), value, true, 0, "association", Collections.emptyList());
    }
    void close() {
        if (engine instanceof AutoCloseable) {
            try { ((AutoCloseable) engine).close(); } catch (Exception ignored) { }
        }
    }

    private State snapshot(boolean accepted, boolean drainCommit) {
        String composing = engine.composingText();
        if (composing == null) composing = "";
        List<String> candidates = engine.candidates();
        if (candidates == null) candidates = Collections.emptyList();
        return new State(composing, Collections.unmodifiableList(new ArrayList<>(candidates)),
                drainCommit ? engine.takeCommit() : "", accepted, engine.cursorPosition());
    }

    private State abbreviationSnapshot() {
        List<String> words = new ArrayList<>();
        for (ZhuyinWordIndex.Entry e : abbreviationEntries) words.add(e.word);
        return new State(abbreviationKeys.toString(), Collections.unmodifiableList(words), "", true, abbreviationKeys.length(), "abbreviation", abbreviationEntries);
    }
    private State mixedSnapshot(boolean accepted) {
        List<MixedChoice> all=new ArrayList<>();
        int input=abbreviationKeys.codePointCount(0,abbreviationKeys.length());
        boolean mergeNative=hasConvertedText(engine.composingText());
        for(ZhuyinWordIndex.Entry e:abbreviationEntries)all.add(new MixedChoice(e.word,e,-1,e.personal,e.frequency,e.word.codePointCount(0,e.word.length())==input));
        List<String> nativeCandidates=mergeNative?engine.candidates():Collections.emptyList();if(nativeCandidates!=null)for(int i=0;i<nativeCandidates.size();i++){
            String word=nativeCandidates.get(i);all.add(new MixedChoice(word,null,i,false,1_000_000L-i,word.codePointCount(0,word.length())==input));
        }
        all.sort((a,b)->{int c=Boolean.compare(b.exactLength,a.exactLength);if(c!=0)return c;c=Boolean.compare(b.personal,a.personal);if(c!=0)return c;return Long.compare(b.frequency,a.frequency);});
        java.util.LinkedHashMap<String,MixedChoice> unique=new java.util.LinkedHashMap<>();for(MixedChoice c:all)unique.putIfAbsent(c.word,c);
        mixedChoices=new ArrayList<>(unique.values());List<String> words=new ArrayList<>();for(MixedChoice c:mixedChoices)words.add(c.word);
        String composing=mergeNative?engine.composingText():abbreviationKeys.toString();
        String kind=mergeNative?"mixed":"abbreviation";
        return new State(composing,Collections.unmodifiableList(words),engine.takeCommit(),accepted,engine.cursorPosition(),kind,Collections.emptyList());
    }
    private static boolean hasConvertedText(String text){
        if(text==null||text.isEmpty())return false;
        for(int i=0;i<text.length();){int cp=text.codePointAt(i);if(cp>Character.MAX_VALUE||"ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙".indexOf(cp)<0)return true;i+=Character.charCount(cp);}
        return false;
    }
    private static final class MixedChoice {
        final String word;final ZhuyinWordIndex.Entry entry;final int engineIndex;final boolean personal,exactLength;final long frequency;
        MixedChoice(String word,ZhuyinWordIndex.Entry entry,int index,boolean personal,long frequency,boolean exact){this.word=word;this.entry=entry;this.engineIndex=index;this.personal=personal;this.frequency=frequency;this.exactLength=exact;}
    }
    private State associationSnapshot() {
        return new State("", Collections.unmodifiableList(new ArrayList<>(associationCandidates)), "", false, 0, "association", Collections.emptyList());
    }

    private static boolean isZhuyin(String key) {
        return key != null && key.length() == 1
                && "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙".contains(key);
    }

    private static final class DisabledEngine implements Engine {
        static final DisabledEngine INSTANCE = new DisabledEngine();
        @Override public void key(String key) { }
        @Override public void backspace() { }
        @Override public void space() { }
        @Override public void enter() { }
        @Override public void choose(int index) { }
        @Override public void moveCursor(String direction) { }
        @Override public int cursorPosition() { return 0; }
        @Override public String composingText() { return ""; }
        @Override public List<String> candidates() { return Collections.emptyList(); }
        @Override public String takeCommit() { return ""; }
        @Override public void clear() { }
    }
}
