package com.simon.voiceime;

/** Install diagnostics before activities, services or receivers initialize. */
public final class PhoneApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        AiSentencePhone.migrateAutoApply(this);
        ImeTelemetry.install(this);
        new Thread(()->{
            java.io.File llm=new java.io.File(getFilesDir(),"models/llm");
            if(!java.nio.file.Files.exists(llm.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))return;
            long removed=0;boolean ok=false;
            try{removed=StorageDiagnostics.clearObsoleteModel(getFilesDir());ok=true;}
            catch(Exception failure){android.util.Log.e("Storage","Obsolete model cleanup incomplete",failure);}
            ImeTelemetry telemetry=ImeTelemetry.get();
            if(telemetry!=null)try{telemetry.record("storage_cleanup","settings",new org.json.JSONObject()
                    .put("category","files").put("confirmed",false).put("removed_bytes",removed).put("ok",ok),false);}
            catch(Exception failure){android.util.Log.e("Storage","Cleanup diagnostic failed",failure);}
        },"obsolete-model-cleanup").start();
    }
}
