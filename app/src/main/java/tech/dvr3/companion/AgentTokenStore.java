package tech.dvr3.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;

final class AgentTokenStore {
    private static final String PREFS = "agent_auth";
    private static final String KEY_TOKEN = "loopback_token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentTokenStore() {}

    static synchronized String getOrCreate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_TOKEN, null);
        if (existing != null && !existing.isBlank()) return existing;

        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        prefs.edit().putString(KEY_TOKEN, token).apply();
        return token;
    }
}
