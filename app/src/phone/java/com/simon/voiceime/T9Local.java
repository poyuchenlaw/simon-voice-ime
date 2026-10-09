package com.simon.voiceime;
import android.content.Context;import java.io.File;import java.nio.charset.StandardCharsets;import java.util.*;import org.json.JSONArray;
final class T9Local implements AutoCloseable {
 static {System.loadLibrary("rime");System.loadLibrary("rime_jni");}
 private final long handle;
 T9Local(Context c){File root=new File(c.getFilesDir(),"rime");handle=nativeCreate(new File(root,"shared").getPath(),new File(root,"user").getPath());if(handle==0)throw new IllegalStateException("T9 Rime session unavailable");}
 List<String> decode(String codes,String context){try{JSONArray a=new JSONArray(new String(nativeQuery(handle,codes,context),StandardCharsets.UTF_8));List<String> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(a.getString(i));return out;}catch(org.json.JSONException e){throw new IllegalStateException("Rime candidate contract",e);}}
 void committed(int index){nativeCommit(handle,index);}
 public void close(){nativeDestroy(handle);}
 private static native long nativeCreate(String shared,String user);private static native void nativeDestroy(long h);private static native byte[] nativeQuery(long h,String codes,String context);private static native void nativeCommit(long h,int index);
}
