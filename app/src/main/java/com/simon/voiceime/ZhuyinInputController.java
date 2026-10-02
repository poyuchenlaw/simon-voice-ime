package com.simon.voiceime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Thin UI-facing adapter. The conversion, candidate ranking and learning remain in libchewing. */
final class ZhuyinInputController {
    interface Engine {
        default boolean previewLiteral(int index){return false;}
        default boolean punctuation(String text){return false;}
        default List<String> optionGroups(){return Collections.emptyList();}
        default boolean keyCaret(int at){return false;}
        default boolean focusAtKey(int at){return false;}
        default int keyPreviewCaret(){return -1;}
        default String sentenceKeys() { return ""; }
        default boolean prepareSentence(String keys,String text) { return false; }
        default void recordTouch(int[] keys,double[] probabilities,boolean[] adjacent) { }
        default boolean regroup(int boundary) { return false; }
        default boolean chooseRegroup(int index) { return false; }
        default List<String> optionKinds(){return Collections.emptyList();}
        default List<String> regroupLabels() { return Collections.emptyList(); }
        default String previewText() { return composingText(); }
        default String phoneticText() { return ""; }
        default int[] previewEditRange() { return null; }
        default List<String> phoneticSyllables() { return Collections.emptyList(); }
        void key(String key);
        default boolean commitsAreRendered(){return false;}
        default boolean preservesUnparsedInput() { return false; }
        void backspace();
        void space();
        void enter();
        void choose(int index);
        default void chooseAndCommit(int index) { choose(index); }
        void moveCursor(String direction);
        default boolean moveCursorToPreviewCharacter(int codePointIndex) { return false; }
        default void moveCursorToEnd() { }
        default int[] previewSelectionRange() { return new int[]{0, 0}; }
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
        final int targetStart;
        final int targetEnd;
        State(String composingText, List<String> candidates, String commitText, boolean accepted, int cursorPosition) {
            this(composingText, candidates, commitText, accepted, cursorPosition, "engine", Collections.emptyList());
        }
        State(String composingText, List<String> candidates, String commitText, boolean accepted, int cursorPosition, String candidateKind, List<ZhuyinWordIndex.Entry> abbreviationEntries) {
            this(composingText, candidates, commitText, accepted, cursorPosition, candidateKind,
                    abbreviationEntries, 0, 0);
        }
        State(String composingText, List<String> candidates, String commitText, boolean accepted, int cursorPosition,
              String candidateKind, List<ZhuyinWordIndex.Entry> abbreviationEntries, int targetStart, int targetEnd) {
            this(composingText,candidates,commitText,accepted,cursorPosition,candidateKind,abbreviationEntries,targetStart,targetEnd,false);
        }
        State(String composingText,List<String> candidates,String commitText,boolean accepted,int cursorPosition,
              String candidateKind,List<ZhuyinWordIndex.Entry> abbreviationEntries,int targetStart,int targetEnd,boolean renderedCommit) {
            this.composingText = composingText;
            this.candidates = candidates;
            this.commitText = renderedCommit ? commitText : renderCommitKeysyms(commitText);
            this.accepted = accepted;
            this.cursorPosition = cursorPosition;
            this.candidateKind = candidateKind;
            this.abbreviationEntries = abbreviationEntries;
            this.targetStart = targetStart;
            this.targetEnd = targetEnd;
        }
    }

    // Zhuyin-page invariant: no committed text contains any physical keysym
    // in this set (including ASCII space/tone 1). Preserve parsed text and
    // fullwidth/service punctuation; render raw keys as their typed glyphs.
    // The native commit boundary enforces the same rule before this UI boundary.
    private static final String COMMIT_KEYSYMS = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347 ";
    private static final String COMMIT_GLYPHS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙ˉ";
    private static String renderCommitKeysyms(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            int index=COMMIT_KEYSYMS.indexOf(c);
            out.append(index<0 ? c : COMMIT_GLYPHS.charAt(index));
        }
        return out.toString();
    }

    private final Engine engine;
    private final ZhuyinWordIndex abbreviationIndex;
    private final StringBuilder abbreviationKeys = new StringBuilder();
    // The engine may reject a syntactically bad stream.  Keep the user's glyphs
    // independently so an engine failure can never turn physical keycodes into text.
    private final StringBuilder rawZhuyinKeys = new StringBuilder();
    private List<ZhuyinWordIndex.Entry> abbreviationEntries = Collections.emptyList();
    private List<MixedChoice> mixedChoices = Collections.emptyList();
    private List<String> associationCandidates = Collections.emptyList();
    private boolean learningEnabled = true;
    private java.util.function.Supplier<Engine> retypeEngineFactory;
    private ZhuyinInputController retype;
    private String retypeOriginal = "", retypeCommitted = "", fixedComposition = "";
    private int retypeStart, retypeEnd, fixedTargetStart, fixedTargetEnd;
    private List<String> fixedReading=Collections.emptyList(),retypeSourceReading=Collections.emptyList();
    private final List<int[]> fixedWordRanges=new ArrayList<>();
    private boolean previewFocused;
    private int keyCaret=-1;
    private List<String> focusedOptionKinds=Collections.emptyList();
    int keyCaret(){return keyCaret;}
    String candidateGroup(int i){List<String> groups=engine.optionGroups();return i>=0&&i<groups.size()?groups.get(i):"word";}
    private int tappedCharacter=-1;
    int tappedCharacter(){return tappedCharacter;}
    int keyPreviewCaret(){return keyCaret<0?-1:engine.keyPreviewCaret();}
    boolean wordFocused(){return previewFocused;}
    State moveCursorToKey(int at){
        if(retype!=null)cancelSecondPass();
        if(!fixedComposition.isEmpty())return snapshot(false,false);
        if(!engine.keyCaret(at))return snapshot(false,false);
        keyCaret=at;previewBoundary=-1;previewFocused=engine.focusAtKey(at);
        if(previewFocused){int[] range=engine.previewEditRange();if(range!=null){previewTargetStart=range[0];previewTargetEnd=range[1];}tappedCharacter=0;int slot=0;for(String syllable:engine.phoneticSyllables()){if(at<slot+syllable.length())break;slot+=syllable.length();tappedCharacter++;}focusedOptionKinds=new ArrayList<>(engine.optionKinds());}
        mixedChoices=Collections.emptyList();abbreviationKeys.setLength(0);abbreviationEntries=Collections.emptyList();
        return snapshot(true,false);
    }
    private int previewBoundary=-1;
    void recordTouch(int[] keys,double[] probabilities,boolean[] adjacent){if(retype!=null)retype.recordTouch(keys,probabilities,adjacent);else engine.recordTouch(keys,probabilities,adjacent);}
    String sentenceKeys() {
        if(retype!=null)return "";
        if(!fixedComposition.isEmpty()&&fixedReading.isEmpty())return "";
        return (String.join("",fixedReading)+engine.sentenceKeys()).replace("ˉ"," ");
    }
    ZhuyinInputController preparedSentence(String keys,String text) {
        if(retypeEngineFactory==null)return null;
        Engine separate=retypeEngineFactory.get();if(separate==null)return null;
        ZhuyinInputController draft=new ZhuyinInputController(separate,abbreviationIndex);
        draft.setLearningEnabled(learningEnabled);draft.setRetypeEngineFactory(retypeEngineFactory);
        if(!separate.prepareSentence(keys,text)){draft.close();return null;}
        return draft;
    }
    int previewBoundary() { return previewBoundary; }
    String previewText() { return retype!=null||!fixedComposition.isEmpty()?state().composingText:engine.previewText(); }
    List<String> phoneticSyllables(){return engine.phoneticSyllables();}
    String phoneticText() {
        if(retype!=null&&!retypeSourceReading.isEmpty())return String.join("",retypeSourceReading.subList(0,retypeStart))+retype.phoneticText()+"│"+String.join("",retypeSourceReading.subList(retypeEnd,retypeSourceReading.size()));
        if(previewBoundary>=0&&previewBoundary<=fixedReading.size()&&engine.composingText().isEmpty())
            return String.join("",fixedReading.subList(0,previewBoundary))+"│"+String.join("",fixedReading.subList(previewBoundary,fixedReading.size()));
        return String.join("",fixedReading)+engine.phoneticText();
    }
    State moveCursorToPreviewBoundary(int boundary) { keyCaret=-1;engine.moveCursorToEnd();
        if(retype!=null)cancelSecondPass();
        if(!fixedComposition.isEmpty()){
            int count=fixedComposition.codePointCount(0,fixedComposition.length());
            if(boundary<0||boundary>count)return snapshot(false,false);
            fixedTargetStart=Math.max(0,boundary-1);fixedTargetEnd=boundary;
            previewBoundary=boundary;previewFocused=boundary>0;return snapshot(true,false);
        }
        String preview=previewText();if(boundary<0||boundary>preview.codePointCount(0,preview.length()))return snapshot(false,false);
        if(!engine.regroup(boundary))return snapshot(false,false);
        previewBoundary=boundary;previewFocused=boundary>0;focusedOptionKinds=new ArrayList<>(engine.optionKinds());
        mixedChoices=Collections.emptyList();associationCandidates=Collections.emptyList();
        // Existing retype transactions use preedit codepoint spans. Locate the
        // syllable immediately before the new boundary from the native glyph row.
        int[] target=engine.previewSelectionRange();
        previewTargetStart=target[0];previewTargetEnd=target[1];
        return snapshot(true,false);
    }
    private int previewTargetStart, previewTargetEnd;
    void setRetypeEngineFactory(java.util.function.Supplier<Engine> factory) { retypeEngineFactory=factory; }
    boolean isSecondPassActive() { return retype != null; }
    State cancelSecondPass() { keyCaret=-1;
        closeRetype(); previewFocused=false;previewBoundary=-1;engine.moveCursorToEnd();
        return snapshot(true,false);
    }
    private void closeRetype() { if(retype!=null){retype.close();retype=null;}retypeCommitted=""; }
    private State retypeState(State part) {
        retypeCommitted += part.commitText;
        String draft = retypeCommitted + part.composingText;
        List<String> choices=new ArrayList<>();
        for(String choice:part.candidates)choices.add(retypeCommitted+choice);
        if(choices.isEmpty()&&!draft.isEmpty())choices.add(draft);
        int from=retypeOriginal.offsetByCodePoints(0,retypeStart),to=retypeOriginal.offsetByCodePoints(0,retypeEnd);
        return new State(retypeOriginal.substring(0,from)+draft+retypeOriginal.substring(to),
            Collections.unmodifiableList(choices),"",part.accepted,retypeStart+draft.codePointCount(0,draft.length()),
            "second_pass",Collections.emptyList(),retypeStart,retypeStart+draft.codePointCount(0,draft.length()));
    }
    private State beginSecondPass() {
        State original=state();String text=original.composingText;
        int start=original.targetStart,end=original.targetEnd;
        int[] decoded=engine.previewEditRange();
        if(fixedComposition.isEmpty()&&decoded!=null){text=engine.previewText();start=decoded[0];end=decoded[1];}
        int count=text.codePointCount(0,text.length());
        if(retypeEngineFactory==null||end<=start||end>count)return null;
        Engine separate=retypeEngineFactory.get();if(separate==null)return null;
        retypeOriginal=text;retypeStart=start;retypeEnd=end;
        retypeSourceReading=fixedReading.isEmpty()?new ArrayList<>(engine.phoneticSyllables()):new ArrayList<>(fixedReading);
        if(retypeSourceReading.size()!=count)retypeSourceReading=Collections.emptyList();
        retype=new ZhuyinInputController(separate,abbreviationIndex);retype.setLearningEnabled(learningEnabled);
        previewFocused=false;previewBoundary=-1;
        return retypeState(retype.state());
    }

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
        if(keyCaret>=0 && (isZhuyin(key)||"backspace".equals(key))){
            if("backspace".equals(key))engine.backspace();else engine.key(key);
            keyCaret=engine.cursorPosition();
            previewFocused=engine.focusAtKey(keyCaret);
            if(previewFocused){int[] range=engine.previewEditRange();if(range!=null){previewTargetStart=range[0];previewTargetEnd=range[1];}focusedOptionKinds=new ArrayList<>(engine.optionKinds());}
            rawZhuyinKeys.setLength(0);return snapshot(true,true);
        }
        if(keyCaret>=0){keyCaret=-1;engine.moveCursorToEnd();}
        if(retype!=null){
            if("enter".equals(key))return state().candidates.isEmpty()?cancelSecondPass():chooseCandidate(0);
            // Repeated deletion before retyping extends the explicit edit span,
            // including words for which the old engine exposed no phrase boundary.
            if("backspace".equals(key)&&retypeCommitted.isEmpty()&&retype.state().composingText.isEmpty()&&retypeStart>0){
                retypeStart--;return retypeState(retype.state());
            }
            return retypeState(retype.press(key));
        }
        if("backspace".equals(key)&&previewFocused){State started=beginSecondPass();if(started!=null)return started;}
        previewFocused=false;previewBoundary=-1;
        if(!fixedComposition.isEmpty()&&engine.composingText().isEmpty()) {
            if("enter".equals(key)||"space".equals(key)){
                String value=fixedComposition;fixedComposition="";fixedReading=Collections.emptyList();fixedWordRanges.clear();engine.clear();
                return new State("",Collections.emptyList(),value,true,0,"engine",Collections.emptyList(),0,0,engine.commitsAreRendered());
            }
            if("backspace".equals(key)){
                fixedComposition=fixedComposition.substring(0,fixedComposition.offsetByCodePoints(fixedComposition.length(),-1));
                if(!fixedReading.isEmpty())fixedReading=new ArrayList<>(fixedReading.subList(0,fixedReading.size()-1));
                int remaining=fixedComposition.codePointCount(0,fixedComposition.length());
                fixedWordRanges.removeIf(range->range[1]>remaining);
                return snapshot(true,false);
            }
        }
        if (isZhuyin(key)) rawZhuyinKeys.append(key);
        if ("backspace".equals(key) && abbreviationKeys.length() > 0) {
            engine.backspace();
            abbreviationKeys.setLength(abbreviationKeys.length() - 1);
            if (rawZhuyinKeys.length() > 0) rawZhuyinKeys.setLength(rawZhuyinKeys.length() - 1);
            abbreviationEntries = abbreviationKeys.length() >= 2 ? abbreviationIndex.lookup(abbreviationKeys.toString()) : Collections.emptyList();
            if (abbreviationKeys.length() == 0) { mixedChoices=Collections.emptyList(); return snapshot(true, true); }
            return mixedSnapshot(true);
        }
        if ("backspace".equals(key) && rawZhuyinKeys.length() > 0) {
            engine.backspace();
            rawZhuyinKeys.setLength(rawZhuyinKeys.length() - 1);
            return snapshot(true, true);
        }
        if (("space".equals(key) || "enter".equals(key)) && rawZhuyinKeys.length() > 0
                && !engine.preservesUnparsedInput()
                && (engine.candidates() == null || engine.candidates().isEmpty())) {
            String glyphs = rawZhuyinKeys.toString();
            rawZhuyinKeys.setLength(0); abbreviationKeys.setLength(0);
            abbreviationEntries = Collections.emptyList(); mixedChoices = Collections.emptyList();
            engine.clear();
            String value=fixedComposition+glyphs;fixedComposition="";fixedWordRanges.clear();
            return new State("", Collections.emptyList(), value, true, 0, "no_parse", Collections.emptyList());
        }
        if (fixedComposition.isEmpty() && isZhuyin(key) && abbreviationIndex != null
                && (abbreviationKeys.length() > 0 || engine.composingText().isEmpty())) {
            if (abbreviationKeys.length() > 0) {
                String extended = abbreviationKeys.toString() + key;
                if (abbreviationIndex.hasPrefix(extended) || abbreviationIndex.hasSegmentedPrefix(extended)) {
                    abbreviationKeys.append(key);
                    engine.key(key);
                    abbreviationEntries = abbreviationIndex.lookup(extended);
                    if (abbreviationKeys.length() >= 4) abbreviationEntries = mergeEntries(abbreviationEntries, abbreviationIndex.segmented(extended));
                    return mixedSnapshot(true);
                }
                abbreviationKeys.setLength(0); abbreviationEntries=Collections.emptyList(); mixedChoices=Collections.emptyList();
            } else if (abbreviationIndex.hasPrefix(key)) {
                abbreviationKeys.append(key);
                engine.key(key);
                abbreviationEntries = abbreviationIndex.lookup(key);
                return mixedSnapshot(true);
            }
        }
        if (abbreviationKeys.length() > 0) { abbreviationKeys.setLength(0); abbreviationEntries=Collections.emptyList(); mixedChoices=Collections.emptyList(); }
        associationCandidates = Collections.emptyList();
        if ("space".equals(key)) { engine.space(); rawZhuyinKeys.setLength(0); }
        else if ("enter".equals(key)) { engine.enter(); rawZhuyinKeys.setLength(0); }
        else if ("backspace".equals(key)) engine.backspace();
        else if (isZhuyin(key)) engine.key(key);
        else return snapshot(false, false);
        return snapshot(true, true);
    }

    State chooseCandidate(int index) {
        keyCaret=-1;
        if((previewBoundary>=0||previewFocused)&&retype==null&&!engine.regroupLabels().isEmpty()) {
            if(!engine.chooseRegroup(index))return snapshot(false,false);
            previewBoundary=-1;previewFocused=false;
            return snapshot(true,false);
        }
        if(retype!=null){
            State preview=retypeState(retype.state());
            if(index<0||index>=preview.candidates.size())return preview;
            String replacement=preview.candidates.get(index);
            List<String> draftReading=new ArrayList<>(retype.engine.phoneticSyllables());
            retype.chooseCandidate(index);
            if(!retypeSourceReading.isEmpty()){
                List<String> joined=new ArrayList<>(retypeSourceReading.subList(0,retypeStart));joined.addAll(draftReading);joined.addAll(retypeSourceReading.subList(retypeEnd,retypeSourceReading.size()));fixedReading=joined;
            }
            int from=retypeOriginal.offsetByCodePoints(0,retypeStart),to=retypeOriginal.offsetByCodePoints(0,retypeEnd);
            int replacementLength=replacement.codePointCount(0,replacement.length());
            int delta=replacementLength-(retypeEnd-retypeStart);
            List<int[]> kept=new ArrayList<>();
            for(int[] range:fixedWordRanges){
                if(range[1]<=retypeStart)kept.add(range);
                else if(range[0]>=retypeEnd)kept.add(new int[]{range[0]+delta,range[1]+delta});
            }
            if(replacementLength>0)kept.add(new int[]{retypeStart,retypeStart+replacementLength});
            fixedWordRanges.clear();fixedWordRanges.addAll(kept);
            fixedComposition=retypeOriginal.substring(0,from)+replacement+retypeOriginal.substring(to);
            fixedTargetStart=retypeStart;fixedTargetEnd=retypeStart+replacement.codePointCount(0,replacement.length());
            closeRetype();engine.clear();rawZhuyinKeys.setLength(0);abbreviationKeys.setLength(0);
            mixedChoices=Collections.emptyList();abbreviationEntries=Collections.emptyList();associationCandidates=Collections.emptyList();
            return snapshot(true,false);
        }
        if (!mixedChoices.isEmpty()) {
            if(index<0||index>=mixedChoices.size())return mixedSnapshot(false);
            MixedChoice selected=mixedChoices.get(index);mixedChoices=Collections.emptyList();
            if(selected.entry!=null){if(learningEnabled){engine.learnPhrase(selected.entry.word,selected.entry.pronunciation);abbreviationIndex.rememberPersonal(selected.entry);}engine.clear();abbreviationKeys.setLength(0);rawZhuyinKeys.setLength(0);abbreviationEntries=Collections.emptyList();return new State("",Collections.emptyList(),selected.entry.word,true,0,"abbreviation",Collections.emptyList());}
            engine.chooseAndCommit(selected.engineIndex);engine.moveCursorToEnd();abbreviationKeys.setLength(0);rawZhuyinKeys.setLength(0);abbreviationEntries=Collections.emptyList();return snapshot(true,true);
        }
        if (abbreviationKeys.length() >= 1) {
            if (index < 0 || index >= abbreviationEntries.size()) return abbreviationSnapshot();
            ZhuyinWordIndex.Entry selected = abbreviationEntries.get(index);
            if (learningEnabled) { engine.learnPhrase(selected.word, selected.pronunciation); abbreviationIndex.rememberPersonal(selected); }
            abbreviationKeys.setLength(0); rawZhuyinKeys.setLength(0); abbreviationEntries = Collections.emptyList();
            return new State("", Collections.emptyList(), selected.word, true, 0, "abbreviation", Collections.emptyList());
        }
        List<String> candidates = engine.candidates();
        if (index < 0 || index >= candidates.size()) return snapshot(false, false);
        engine.chooseAndCommit(index); engine.moveCursorToEnd(); rawZhuyinKeys.setLength(0);
        return snapshot(true, true);
    }
    String candidateOrigin(int index){
        if((previewBoundary>=0||previewFocused)&&index>=0&&index<focusedOptionKinds.size())return focusedOptionKinds.get(index);
        if(!mixedChoices.isEmpty()&&index>=0&&index<mixedChoices.size())return mixedChoices.get(index).entry==null?"engine":"abbreviation";
        return abbreviationKeys.length()>=1?"abbreviation":"engine";
    }

    State moveCursorLeft() { if(previewBoundary>=0)return moveCursorToPreviewBoundary(Math.max(0,previewBoundary-1));mixedChoices=Collections.emptyList(); engine.moveCursor("left"); return snapshot(true, true); }
    State moveCursorRight() { if(previewBoundary>=0)return moveCursorToPreviewBoundary(Math.min(previewText().codePointCount(0,previewText().length()),previewBoundary+1));mixedChoices=Collections.emptyList(); engine.moveCursor("right"); return snapshot(true, true); }
    State moveCursorToPreviewCharacter(int codePointIndex) { tappedCharacter=codePointIndex;keyCaret=-1;engine.moveCursorToEnd();
        previewBoundary=-1;
        if(retype!=null)cancelSecondPass();
        if(previewFocused&&codePointIndex>=previewTargetStart&&codePointIndex<previewTargetEnd)return cancelSecondPass();
        int fixed=fixedComposition.codePointCount(0,fixedComposition.length());
        if(codePointIndex>=0&&codePointIndex<fixed){
            fixedTargetStart=codePointIndex;fixedTargetEnd=codePointIndex+1;
            for(int[] range:fixedWordRanges)if(codePointIndex>=range[0]&&codePointIndex<range[1]){fixedTargetStart=range[0];fixedTargetEnd=range[1];break;}
            previewFocused=true;
            return snapshot(true,false);
        }
        mixedChoices = Collections.emptyList();
        associationCandidates = Collections.emptyList();
        previewFocused=false;
        boolean moved = engine.moveCursorToPreviewCharacter(codePointIndex-fixed);
        focusedOptionKinds=new ArrayList<>(engine.optionKinds());
        State focused=snapshot(moved,false);
        previewTargetStart=focused.targetStart;previewTargetEnd=focused.targetEnd;
        int count=focused.composingText.codePointCount(0,focused.composingText.length());
        int[] nativeRange=engine.previewEditRange();
        if(moved&&nativeRange!=null){previewTargetStart=nativeRange[0];previewTargetEnd=nativeRange[1];}
        boolean targetable=moved||(retypeEngineFactory!=null&&codePointIndex>=0&&codePointIndex<count);
        if(nativeRange==null&&targetable&&codePointIndex>=0&&codePointIndex<count&&(!moved||previewTargetEnd<=previewTargetStart
                || (previewTargetStart==0&&previewTargetEnd>=count))){
            // Rime may expose one phrase spanning the whole sentence. Use the
            // visible syllable token (or tapped Han character) for the retype
            // transaction so a middle edit cannot delete both surrounding parts.
            int utf=focused.composingText.offsetByCodePoints(0,codePointIndex);
            int left=utf,right=utf+Character.charCount(focused.composingText.codePointAt(utf));
            boolean glyph=isZhuyin(new String(Character.toChars(focused.composingText.codePointAt(utf))));
            if(glyph){
                while(left>0&&focused.composingText.charAt(left-1)!=' ')left--;
                while(right<focused.composingText.length()&&focused.composingText.charAt(right)!=' ')right++;
            }
            previewTargetStart=focused.composingText.codePointCount(0,left);
            previewTargetEnd=focused.composingText.codePointCount(0,right);
        }
        if(moved&&engine.previewLiteral(codePointIndex)){keyCaret=engine.cursorPosition();previewFocused=false;return snapshot(true,false);}
        previewFocused=targetable;
        return snapshot(targetable, false);
    }

    State clear() { keyCaret=-1; previewBoundary=-1;fixedReading=Collections.emptyList();retypeSourceReading=Collections.emptyList();closeRetype();fixedComposition="";fixedWordRanges.clear();previewFocused=false; abbreviationKeys.setLength(0); rawZhuyinKeys.setLength(0); abbreviationEntries = Collections.emptyList(); mixedChoices=Collections.emptyList(); associationCandidates = Collections.emptyList(); engine.clear(); return snapshot(true, true); }
    State punctuation(String text) {
        if(previewText().isEmpty())return new State("",Collections.emptyList(),text,true,0,"engine",Collections.emptyList(),0,0,true);
        if(retype!=null)return retypeState(retype.punctuation(text));
        if(!engine.punctuation(text)){System.err.println("Zhuyin punctuation retained: boundary unavailable");return snapshot(false,false);}
        keyCaret=engine.cursorPosition();previewFocused=false;previewBoundary=-1;
        rawZhuyinKeys.setLength(0);abbreviationKeys.setLength(0);mixedChoices=Collections.emptyList();abbreviationEntries=Collections.emptyList();
        return snapshot(true,true);
    }
    State flushForPunctuation() {
        if(retype!=null)cancelSecondPass();
        if(!fixedComposition.isEmpty()&&engine.composingText().isEmpty())return press("enter");
        if (engine.composingText() == null || engine.composingText().isEmpty()) return snapshot(true, true);
        abbreviationKeys.setLength(0); abbreviationEntries = Collections.emptyList(); mixedChoices = Collections.emptyList(); associationCandidates = Collections.emptyList();
        engine.space();
        return snapshot(true, true);
    }
    State state() { return retype==null?snapshot(false, false):retypeState(retype.state()); }
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
    void setLearningEnabled(boolean enabled) { learningEnabled = enabled; if(retype!=null)retype.setLearningEnabled(enabled); }
    void close() {
        closeRetype();
        if (engine instanceof AutoCloseable) {
            try { ((AutoCloseable) engine).close(); } catch (Exception ignored) { }
        }
    }

    private State snapshot(boolean accepted, boolean drainCommit) {
        String composing = engine.composingText();
        if (composing == null) composing = "";
        List<String> candidates = (previewBoundary>=0||previewFocused)&&!engine.regroupLabels().isEmpty()?engine.regroupLabels():engine.candidates();
        if (candidates == null) candidates = Collections.emptyList();
        if (!engine.preservesUnparsedInput() && rawZhuyinKeys.length() > 0 && candidates.isEmpty()) {
            return new State(fixedComposition+rawZhuyinKeys.toString(), Collections.emptyList(), "", accepted,
                    rawZhuyinKeys.codePointCount(0, rawZhuyinKeys.length()), "no_parse", Collections.emptyList());
        }
        int[] target = engine.previewSelectionRange();
        int targetStart = target != null && target.length > 1 ? target[0] : 0;
        int targetEnd = target != null && target.length > 1 ? target[1] : 0;
        if(previewFocused&&fixedComposition.isEmpty()){targetStart=previewTargetStart;targetEnd=previewTargetEnd;}
        String committed=drainCommit?engine.takeCommit():"";
        if(!fixedComposition.isEmpty()){
            if(!committed.isEmpty()){committed=fixedComposition+committed;fixedComposition="";fixedReading=Collections.emptyList();fixedWordRanges.clear();}
            else {
                int prefix=fixedComposition.codePointCount(0,fixedComposition.length());
                if(composing.isEmpty()||previewFocused){targetStart=fixedTargetStart;targetEnd=fixedTargetEnd;}
                else {targetStart+=prefix;targetEnd+=prefix;}
                composing=fixedComposition+composing;
            }
        }
        return new State(composing, Collections.unmodifiableList(new ArrayList<>(candidates)),
                committed, accepted, engine.cursorPosition(), previewBoundary>=0?"regroup":previewFocused?"word":"engine",
                Collections.emptyList(), targetStart, targetEnd,engine.commitsAreRendered());
    }

    private State abbreviationSnapshot() {
        List<String> words = new ArrayList<>();
        for (ZhuyinWordIndex.Entry e : abbreviationEntries) words.add(e.word);
        return new State(abbreviationKeys.toString(), Collections.unmodifiableList(words), "", true, abbreviationKeys.length(), "abbreviation", abbreviationEntries);
    }
    private static List<ZhuyinWordIndex.Entry> mergeEntries(List<ZhuyinWordIndex.Entry> first, List<ZhuyinWordIndex.Entry> second) {
        java.util.LinkedHashMap<String,ZhuyinWordIndex.Entry> unique = new java.util.LinkedHashMap<>();
        for (ZhuyinWordIndex.Entry e : first) unique.putIfAbsent(e.word, e);
        for (ZhuyinWordIndex.Entry e : second) unique.putIfAbsent(e.word, e);
        List<ZhuyinWordIndex.Entry> result = new ArrayList<>(unique.values());
        result.sort((a,b)->Long.compare(b.frequency,a.frequency));
        return result;
    }
    private State mixedSnapshot(boolean accepted) {
        List<MixedChoice> all=new ArrayList<>();
        int input=abbreviationKeys.codePointCount(0,abbreviationKeys.length());
        // Preserve both derivations.  An abbreviated entry must never hide an
        // engine candidate merely because its preedit is still Zhuyin glyphs;
        // the mixed ranking lets the complete-syllable parse occupy the first
        // visible row while leaving every 6.41 abbreviation derivation intact.
        boolean mergeNative=hasConvertedText(engine.composingText())
                || (engine instanceof RimeZhuyinEngine
                && engine.candidates()!=null&&!engine.candidates().isEmpty());
        for(ZhuyinWordIndex.Entry e:abbreviationEntries)all.add(new MixedChoice(e.word,e,-1,e.personal,e.frequency,e.word.codePointCount(0,e.word.length())==input));
        List<String> nativeCandidates=mergeNative?engine.candidates():Collections.emptyList();if(nativeCandidates!=null)for(int i=0;i<nativeCandidates.size();i++){
            String word=nativeCandidates.get(i);all.add(new MixedChoice(word,null,i,false,1_000_000L-i,word.codePointCount(0,word.length())==input));
        }
        all.sort((a,b)->{double sa=Math.log10(Math.max(1L,a.frequency))+(a.exactLength?0.5:0)+(a.personal?2:0);double sb=Math.log10(Math.max(1L,b.frequency))+(b.exactLength?0.5:0)+(b.personal?2:0);return Double.compare(sb,sa);});
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
