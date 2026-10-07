package com.simon.voiceime;
import android.app.Activity;import android.os.Bundle;import android.widget.*;
/** Test APK only: explicit incoming semantic node, synthetic non-client text. */
public class ContextSandboxActivity extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
  TextView message=new TextView(this);message.setId(getResources().getIdentifier("incoming_message","id",getPackageName()));message.setText("請確認損害賠償");message.setTextSize(24);root.addView(message);
  EditText field=new EditText(this);field.setId(android.R.id.edit);field.setContentDescription("context_editor");field.setHint("回覆");if(getIntent().getBooleanExtra("password",false))field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);root.addView(field);EditText second=new EditText(this);second.setId(android.R.id.text1);second.setContentDescription("context_editor_second");second.setHint("第二欄");root.addView(second);setContentView(root);field.requestFocus();
 }
}
