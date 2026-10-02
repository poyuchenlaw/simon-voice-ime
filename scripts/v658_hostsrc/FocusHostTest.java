package com.simon.voiceime;
public class FocusHostTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void type(ZhuyinInputController c,String keys){for(int i=0;i<keys.length();i++)c.press(keys.substring(i,i+1));}
 public static void main(String[] a)throws Exception{try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
 ZhuyinInputController c=new ZhuyinInputController(e);type(c,"ㄉㄠˋㄇㄚˇ");System.out.println("ENGINE="+c.previewText());
 var f=c.moveCursorToPreviewCharacter(0);check(f.targetStart==0&&f.targetEnd==2,"highlight whole engine word");
 check(f.candidates.contains("道")&&f.candidates.contains("到"),"char group alternatives");int firstChar=-1;
 java.util.Set<String> seen=new java.util.HashSet<>();for(int i=0;i<f.candidates.size();i++){check(seen.add(f.candidates.get(i)),"duplicate");if(c.candidateGroup(i).equals("char")&&firstChar<0)firstChar=i;if(firstChar>=0)check(c.candidateGroup(i).equals("char")||c.candidateOrigin(i).equals("slip"),"word before char; only slip repairs may follow char group");}
 int rank=f.candidates.indexOf("道");check(c.candidateGroup(rank).equals("char"),"char metadata");check(c.chooseCandidate(rank).commitText.isEmpty(),"local field unchanged");check(c.previewText().equals("道馬"),"suffix pinned");check(!c.wordFocused(),"focus returned");check(!c.state().candidates.isEmpty(),"default focus must offer commit after edit");
 check(c.chooseCandidate(0).commitText.equals("道馬"),"default pick commits pinned preview");
 c.clear();type(c,"ㄉㄠˋㄇㄚˇ");f=c.moveCursorToPreviewCharacter(0);int word=-1;for(int i=0;i<f.candidates.size();i++)if(c.candidateGroup(i).equals("word")&&f.candidates.get(i).codePointCount(0,f.candidates.get(i).length())==2){word=i;break;}check(word>=0,"word alternative");String expected=f.candidates.get(word);check(c.chooseCandidate(word).commitText.isEmpty(),"word doesn't commit");check(c.previewText().equals(expected),"word group replaces word");
 c.clear();type(c,"ㄉㄠˋㄇㄚˇ");f=c.moveCursorToPreviewCharacter(0);c.chooseCandidate(f.candidates.indexOf("道"));
 c.moveCursorToKey(5);c.press("backspace");check(c.previewText().startsWith("道"),"delete must preserve accepted outside glyph");c.press("ㄚ");check(c.previewText().startsWith("道"),"insert must preserve accepted outside glyph: "+c.previewText());check(c.sentenceKeys().equals("ㄉㄠˋㄇㄚˇ"),"restored keys");
 c.clear();type(c,"ㄉㄠˋㄇㄚˇ");c.punctuation("，");type(c,"ㄋㄧˇ");f=c.moveCursorToKey(1);check(c.wordFocused()&&!f.candidates.isEmpty(),"row2 alternatives");String original=c.previewText();String suffix=original.substring(original.indexOf("，"));c.press("backspace");c.press("ㄉ");check(c.previewText().endsWith(suffix),"row2 other word remains displayed");check(c.state().commitText.isEmpty(),"row2 field unchanged");
 System.out.println("PASS word/char groups, dedup, range, single-character pin, full-word replacement, row2 caret alternatives and stable outside glyph");
 }}
}
