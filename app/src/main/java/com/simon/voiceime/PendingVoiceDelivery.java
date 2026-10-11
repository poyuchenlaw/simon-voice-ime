package com.simon.voiceime;

/** Public notices never contain the private transcription. */
final class PendingVoiceDelivery {
    static final String CLIPBOARD_NOTICE="語音文字已放入剪貼簿，請長按貼上";
    static final String NOTIFICATION_ENTRY="開啟通知以接收補送提示";
    static boolean sameField(String saved,int generation,String current,int currentGeneration,boolean focused,boolean protectedField) {
        return saved!=null&&!saved.isEmpty()&&saved.equals(current)&&generation==currentGeneration&&focused&&!protectedField;
    }
    static boolean permissionAllowed(boolean recording,boolean finalizing) {return !recording&&!finalizing;}
    static boolean offerNotification(boolean granted,boolean offered,boolean declined) {return !granted&&!offered&&!declined;}
    static Integer charsBeforeCursor(CharSequence text) {return text==null?null:Math.min(1000,Character.codePointCount(text,0,text.length()));}
}
