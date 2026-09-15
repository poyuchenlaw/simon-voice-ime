package com.simon.voiceime;

import android.net.Uri;
import org.json.JSONException;
import org.json.JSONObject;
import okhttp3.FormBody;
import okhttp3.MultipartBody;

/** Version metadata for WTI requests; download and local-model requests stay separate. */
final class AppVersion {
    private AppVersion() {}

    static MultipartBody.Builder withAppVersion(MultipartBody.Builder body) {
        return body.addFormDataPart("app_version", BuildConfig.VERSION_NAME);
    }

    static FormBody.Builder withAppVersion(FormBody.Builder body) {
        return body.add("app_version", BuildConfig.VERSION_NAME);
    }

    static JSONObject withAppVersion(JSONObject body) throws JSONException {
        return body.put("app_version", BuildConfig.VERSION_NAME);
    }

    static String controlMessage(String type) {
        try {
            return withAppVersion(new JSONObject()).put("type", type).toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot encode versioned control message", e);
        }
    }

    // GET, DELETE and WebSocket handshakes carry the field in the query string.
    static String withAppVersion(String url) {
        return Uri.parse(url).buildUpon()
                .appendQueryParameter("app_version", BuildConfig.VERSION_NAME).build().toString();
    }
}
