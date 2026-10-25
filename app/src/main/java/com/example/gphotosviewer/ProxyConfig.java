package com.example.gphotosviewer;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

/**
 * The "Stream via proxy" settings: on/off, the proxy address and the access key.
 * Saved in a private preferences file that is left out of Android backups.
 */
final class ProxyConfig {

    static final String PREFS = "proxy_prefs";
    static final int MIN_KEY_LENGTH = 12;

    private static final String K_ENABLED = "enabled";
    private static final String K_BASE = "base";
    private static final String K_KEY = "key";

    final boolean enabled;
    final String base; // like https://name.workers.dev (no slash at the end), or ""
    final String key;

    private ProxyConfig(boolean enabled, String base, String key) {
        this.enabled = enabled;
        this.base = base == null ? "" : base;
        this.key = key == null ? "" : key;
    }

    static ProxyConfig load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new ProxyConfig(
                p.getBoolean(K_ENABLED, false),
                p.getString(K_BASE, ""),
                p.getString(K_KEY, ""));
    }

    static void save(Context c, boolean enabled, String base, String key) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(K_ENABLED, enabled)
                .putString(K_BASE, base)
                .putString(K_KEY, key)
                .apply();
    }

    private static final String K_LAST_ERROR = "last_error";

    /** Remembers the latest proxy problem so Proxy settings can show it. */
    static void saveLastError(Context c, String message) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_LAST_ERROR, message).apply();
    }

    static String loadLastError(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_LAST_ERROR, "");
    }

    /** True only when the toggle is on and both the address and key are filled in. */
    boolean isReady() {
        return enabled && !base.isEmpty() && key.length() >= MIN_KEY_LENGTH;
    }

    /** A short text that changes whenever any setting changes. */
    String signature() {
        return enabled + "|" + base + "|" + key;
    }

    /**
     * Cleans up what was typed: accepts "name.workers.dev" or
     * "https://name.workers.dev/" and returns "https://name.workers.dev".
     * Returns null when it cannot be used (not https, no dot, extra path...).
     */
    static String normalizeBase(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "https://" + s;
        try {
            URI u = new URI(s);
            if (!"https".equalsIgnoreCase(u.getScheme())) return null;
            String host = u.getHost();
            if (host == null || !host.contains(".")) return null;
            if (u.getUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
                return null;
            }
            String path = u.getRawPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) return null;
            int port = u.getPort();
            return "https://" + host.toLowerCase(Locale.ROOT)
                    + ((port > 0 && port != 443) ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }
}
