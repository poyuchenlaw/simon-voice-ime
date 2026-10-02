#!/usr/bin/env python3
"""Mechanical replay of the actual service metadata helper; does not test UI dispatch."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parent.parent
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
s=(root/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
a=s.index('    private void recordCursorKeyDifferences(');b=s.index('    private ZhuyinInputController.State pressZhuyinWithTouch',a);method=s[a:b]
java='''import java.util.*;
public class CursorMetadataHost {
 int cursorEditSlot=-1,cursorEditSyllable=-1;String cursorFromKey="",cursorToKey="",cursorInteractionKind="key_caret";
 List<String> rows=new ArrayList<>();
 void recordCursorEvent(String kind,String action,String origin,int rank,int removed,int inserted){rows.add(cursorEditSlot+"|"+cursorEditSyllable+"|"+cursorFromKey+"|"+cursorToKey+"|"+removed+"|"+inserted);}
'''+method+'''
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){CursorMetadataHost host=new CursorMetadataHost();
  host.recordCursorKeyDifferences("ㄅㄨˊㄧㄠˋㄩㄢ ㄨㄤˇㄋㄧˇ","ㄋㄧˇ",Arrays.asList("ㄅㄨˊ","ㄧㄠˋ","ㄩㄢ ","ㄨㄤˇ","ㄋㄧˇ"),"engine");
  check(host.rows.isEmpty(),"prefix consumption must not be labelled symbol deletion, events="+host.rows);
  host.recordCursorKeyDifferences("ㄇㄧㄥˊㄒㄧㄢˇ","ㄇㄧㄥˊㄒㄧㄢˇ",Arrays.asList("ㄇㄧㄥˊ","ㄒㄧㄢˇ"),"homophone");
  check(host.rows.isEmpty(),"homophone has no physical key edit");
  host.recordCursorKeyDifferences("ㄇㄧㄥˊㄒㄧㄢˇ","ㄇㄧㄥˊㄒㄧㄞˇ",Arrays.asList("ㄇㄧㄥˊ","ㄒㄧㄢˇ"),"neighbour");
  check(host.rows.equals(Collections.singletonList("6|1|ㄢ|ㄞ|1|1")),"one neighbour edit exact slot, keys and counts: "+host.rows);
  System.out.println("PASS consumed prefix suppression, homophone suppression, exact neighbour metadata");
 }
}
'''
(out/'CursorMetadataHost.java').write_text(java)
jdk='/home/simon/.local/jdk/jdk-17.0.2/bin/'
subprocess.run([jdk+'javac','-encoding','UTF-8','-d',str(out),str(out/'CursorMetadataHost.java')],check=True)
raise SystemExit(subprocess.run([jdk+'java','-cp',str(out),'CursorMetadataHost']).returncode)
