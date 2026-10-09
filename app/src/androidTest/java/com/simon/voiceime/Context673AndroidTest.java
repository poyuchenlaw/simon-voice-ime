package com.simon.voiceime;
import junit.framework.TestCase;
import androidx.test.platform.app.InstrumentationRegistry;
/** The retired general screen reader must not be exposed by the installed package. */
public class Context673AndroidTest extends TestCase {
 public void testGeneralScreenServiceRemoved()throws Exception{
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  android.content.ComponentName general=new android.content.ComponentName(c.getPackageName(),c.getPackageName()+".ConversationContextService");
  try{c.getPackageManager().getServiceInfo(general,0);fail("general screen service still installed");}
  catch(android.content.pm.PackageManager.NameNotFoundException expected){}
  assertNotNull(c.getPackageManager().getServiceInfo(new android.content.ComponentName(c,LineContextAccessibilityService.class),0));
 }
}
