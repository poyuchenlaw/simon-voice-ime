package com.simon.voiceime;
import java.util.Collections;
import java.util.List;
final class ZhuyinWordIndex {
 List<Entry> selectionCandidates(String text){return Collections.emptyList();}
  static final class Entry {
    final String key,word,pronunciation; final long frequency; final boolean personal;
    Entry(String key,String word,String pronunciation,long frequency,boolean personal){this.key=key;this.word=word;this.pronunciation=pronunciation;this.frequency=frequency;this.personal=personal;}
  }
  boolean hasPrefix(String key){return false;} boolean hasSegmentedPrefix(String key){return false;}
  List<Entry> lookup(String key){return Collections.emptyList();} List<Entry> segmented(String key){return Collections.emptyList();}
  void rememberPersonal(Entry entry){}
  void rememberPersonal(List<String[]> phrases){}
}
