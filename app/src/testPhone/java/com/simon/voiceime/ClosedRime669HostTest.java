package com.simon.voiceime;
public final class ClosedRime669HostTest {
 public static void main(String[] args) {
  RimeZhuyinEngine engine=new RimeZhuyinEngine(args[0],args[1]);
  engine.key("ㄅ"); engine.close(); engine.clear(); engine.clear(); engine.close();
  if(!engine.previewText().isEmpty()||!engine.sentenceKeys().isEmpty())throw new AssertionError("closed engine must stay empty");
  System.out.println("PASS close-clear-clear-close remains empty");
 }
}
