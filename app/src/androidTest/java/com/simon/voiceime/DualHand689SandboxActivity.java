package com.simon.voiceime;
import android.app.Activity;import android.os.Bundle;import android.view.*;import android.widget.*;
/** Test-only host with status/title bars and bottom-centred editor, adjustResize. */
public class DualHand689SandboxActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);setTitle("6.89 橫向輸入驗證");FrameLayout root=new FrameLayout(this);EditText field=new EditText(this);field.setId(android.R.id.edit);field.setContentDescription("dualhand689_editor");field.setHint("測試文字");FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);root.addView(field,p);setContentView(root);field.requestFocus();}
}
