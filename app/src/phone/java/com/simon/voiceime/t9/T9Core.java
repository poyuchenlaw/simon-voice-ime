package com.simon.voiceime.t9;
import java.nio.charset.StandardCharsets;
/** The generated Rust inventory is the only phonotactic/character table. */
public final class T9Core implements AutoCloseable {
 static {System.loadLibrary("zhuyin_t9_core");}
 private long handle=create();
 public boolean push(int key){return push(handle,key);}
 public int mask(){return mask(handle);}
 public boolean complete(){return complete(handle);}
 public boolean closed(){return closed(handle);}
 public String codes(){return new String(copy(handle,0),StandardCharsets.UTF_8);}
 public byte[] snapshot(){return copy(handle,1);}
 public boolean restore(byte[] bytes){return restore(handle,bytes);}
 public void backspace(){backspace(handle);}
 public void clear(){restore(new byte[0]);}
 public boolean legal(String text,String codes){String[] parts=codes.trim().isEmpty()?new String[0]:codes.split(" ");int[] c=new int[parts.length];try{for(int i=0;i<c.length;i++)c[i]=Integer.parseInt(parts[i]);}catch(NumberFormatException e){return false;}return text.codePointCount(0,text.length())==c.length&&check(text.getBytes(StandardCharsets.UTF_8),c);}
 public static String table(){return data(0,0,0);}
 public static String characters(int code){return data(1,code,0);}
 public static String reading(int ch,int code){return data(2,code,ch);}
 private static String data(int kind,int code,int ch){return new String(table(kind,code,ch),StandardCharsets.UTF_8);}
 public void close(){long old=handle;handle=0;if(old!=0)destroy(old);}
 private static native long create(); private static native void destroy(long h);
 private static native boolean push(long h,int k);private static native int mask(long h);
 private static native void backspace(long h);private static native boolean complete(long h);private static native boolean closed(long h);
 private static native byte[] copy(long h,int kind);private static native boolean restore(long h,byte[] bytes);
 private static native boolean check(byte[] text,int[] codes);private static native byte[] table(int kind,int code,int ch);
}
