package com.simon.voiceime;

/** Install diagnostics before activities, services or receivers initialize. */
public final class PhoneApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        AiSentencePhone.migrateAutoApply(this);
        ImeTelemetry.install(this);
    }
}
