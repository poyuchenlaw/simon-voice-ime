package com.simon.voiceime;

import android.content.SharedPreferences;

/** Shared authentication defaults for voice, settings, telemetry, and app services. */
final class AuthConfig {
    static final String DEFAULT_PASSWORD = "guangxin_voice_2026";

    private AuthConfig() {}

    static String password(SharedPreferences preferences) {
        String saved = preferences.getString("auth_password", DEFAULT_PASSWORD);
        return saved == null || saved.isEmpty() ? DEFAULT_PASSWORD : saved;
    }

    static String authorizationHeader(String savedPassword) {
        String password = savedPassword == null || savedPassword.isEmpty()
                ? DEFAULT_PASSWORD : savedPassword;
        return "Bearer " + password;
    }
}
