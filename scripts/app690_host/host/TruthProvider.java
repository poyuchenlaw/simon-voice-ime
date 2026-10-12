package com.simon.ime.host690;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.*;import android.graphics.Rect;import android.text.Layout;import android.view.*;import android.view.inputmethod.*;import android.widget.*;import org.json.*;import java.util.concurrent.*;
/** Local diagnostic host only. Its EditText Layout is independent of CursorAnchorInfo. */
public class TruthProvider extends ContentProvider {
 static volatile App690HostActivity activity;
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extra){
  FutureTask<Bundle> task=new FutureTask<>(()->{App690HostActivity a=activity;if(a==null)throw new IllegalStateException("host not started");EditText e=a.findViewById(android.R.id.edit);
   if(method.equals("text")){e.setText(extra.getString("text",""));e.setSelection(e.length());}
   if(method.equals("select"))e.setSelection(extra.getInt("start"),extra.getInt("end",extra.getInt("start")));
   if(method.equals("top")){if(a.getActionBar()!=null)a.getActionBar().hide();FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)e.getLayoutParams();p.gravity=Gravity.TOP;e.setLayoutParams(p);e.setPadding(e.getPaddingLeft(),0,e.getPaddingRight(),0);e.setTextSize(12);}
   if(method.equals("password")){e.setInputType(extra.getBoolean("enabled")?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_TEXT);((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(e);}
   if(method.equals("reopen")){e.requestFocus();((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(e,InputMethodManager.SHOW_IMPLICIT);}
   if(method.equals("finish"))a.finish();
   JSONObject json=new JSONObject().put("text",e.getText().toString()).put("start",e.getSelectionStart()).put("end",e.getSelectionEnd());Rect visible=new Rect();e.getWindowVisibleDisplayFrame(visible);int[] at=new int[2];e.getLocationOnScreen(at);json.put("visible",new JSONArray(new int[]{visible.left,visible.top,visible.right,visible.bottom})).put("editor_bounds",new JSONArray(new int[]{at[0],at[1],at[0]+e.getWidth(),at[1]+e.getHeight()})).put("density",e.getResources().getDisplayMetrics().density);
   Layout l=e.getLayout();if(l!=null&&e.length()>0){int index=Math.max(0,Math.min(e.length()-1,e.getSelectionStart()-1));if(index>0&&Character.isLowSurrogate(e.getText().charAt(index)))index--;int next=index+Character.charCount(Character.codePointAt(e.getText(),index)),line=l.getLineForOffset(index);float x=at[0]+e.getTotalPaddingLeft()-e.getScrollX(),y=at[1]+e.getTotalPaddingTop()-e.getScrollY();float left=l.getPrimaryHorizontal(index),right=l.getPrimaryHorizontal(next);json.put("character_bounds",new JSONArray(new float[]{x+Math.min(left,right),y+l.getLineTop(line),x+Math.max(left,right),y+l.getLineBottom(line)})).put("index",index).put("truth_source","EditText.Layout+getLocationOnScreen");}
   Bundle b=new Bundle();b.putString("json",json.toString());return b;
  });new Handler(Looper.getMainLooper()).post(task);try{return task.get(5,TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException(ex);}
 }
 public Cursor query(Uri u,String[]p,String s,String[]a,String o){throw new UnsupportedOperationException();}public String getType(Uri u){return null;}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[]a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[]a){throw new UnsupportedOperationException();}
}
