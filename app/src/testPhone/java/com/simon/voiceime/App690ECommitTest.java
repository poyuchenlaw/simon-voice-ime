package com.simon.voiceime;
import java.util.*;import org.junit.*;import static org.junit.Assert.*;
/** Actual controller transactions, with literal known readings at the native-engine boundary. */
public class App690ECommitTest {
 static class Bank extends CommitTelemetryTest.Conversion {
  Bank(){text="銀行";keys="ㄧㄣˊㄏㄤˊ";}
  public List<String> phoneticSyllables(){return text.equals("銀行")?Arrays.asList("ㄧㄣˊ","ㄏㄤˊ"):text.equals("行")?Collections.singletonList("ㄏㄤˊ"):Collections.emptyList();}
 }
 @Before public void reset(){CorrectionCommitRecord.clear();}
 @Test public void fullBankCommitCapturesBeforeNativePartsAreDrained(){ZhuyinInputController c=new ZhuyinInputController(new Bank(),null);assertEquals("銀行",c.press("enter").commitText);assertEquals(Arrays.asList("ㄧㄣ","ㄏㄤ"),CorrectionCommitRecord.take(c,"銀行"));}
 @Test public void partialBankCommitCapturesOnlyConsumedReading(){Bank e=new Bank(){public void chooseAndCommit(int i){commit="銀";text="行";keys="ㄏㄤˊ";}};ZhuyinInputController c=new ZhuyinInputController(e,null);assertEquals("銀",c.chooseCandidate(0).commitText);assertEquals(Collections.singletonList("ㄧㄣ"),CorrectionCommitRecord.take(c,"銀"));assertEquals("行",c.press("enter").commitText);assertEquals(Collections.singletonList("ㄏㄤ"),CorrectionCommitRecord.take(c,"行"));}
 @Test public void protectedControllerDoesNotStageAnyCommitReading(){ZhuyinInputController c=new ZhuyinInputController(new Bank(),null);c.setLearningEnabled(false);assertEquals("銀行",c.press("enter").commitText);assertNull(CorrectionCommitRecord.take(c,"銀行"));}
 @Test public void installedAiStillCommitsViaSameBoundary(){ZhuyinInputController before=new ZhuyinInputController(new Bank(),null),after=new ZhuyinInputController(new Bank(),null);after.inheritCommitTelemetry(before);assertEquals("銀行",after.press("enter").commitText);assertEquals(Arrays.asList("ㄧㄣ","ㄏㄤ"),CorrectionCommitRecord.take(after,"銀行"));}
}
