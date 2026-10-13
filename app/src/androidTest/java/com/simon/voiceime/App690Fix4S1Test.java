package com.simon.voiceime;
import android.os.*;import android.util.Printer;import org.json.*;
/** Keep the established gesture assertions; add exact >=100ms dispatch observations. */
public class App690Fix4S1Test extends App690FS1Test {
    @Override public void testThirtyColdSwipes()throws Exception {
        JSONArray dispatches=new JSONArray();final long[] begin={0};final String[] header={""};Printer previous=main(()->(Printer)field(Looper.getMainLooper(),"mLogging"));
        main(()->{Looper.getMainLooper().setMessageLogging(line->{long now=SystemClock.uptimeMillis();previous.println(line);if(line.startsWith(">>>>>")){begin[0]=now;header[0]=line;}else if(line.startsWith("<<<<<")&&now-begin[0]>=100)try{dispatches.put(new JSONObject().put("ts",System.currentTimeMillis()).put("ms",now-begin[0]).put("header",header[0]));}catch(JSONException e){throw new RuntimeException(e);}});return null;});
        try{super.testThirtyColdSwipes();}finally{main(()->{Looper.getMainLooper().setMessageLogging(previous);return null;});save("fix4-s1-dispatches.json",dispatches);}
    }
}
