package cat.narezany.margyt;

import android.content.Context;
import android.content.SharedPreferences;

/** User-selected number of videos for TikTok's offline mode. */
public final class OfflineLimit {
    private OfflineLimit() {}
    public static final String KEY = "offline_video_limit";
    public static final int DEFAULT = 480;
    public static final int MIN = 1;
    public static final int MAX = 10000;
    private static volatile Integer cached;

    public static int get() {
        Integer known = cached;
        if (known != null) return known;
        SharedPreferences prefs = prefs();
        int value = DEFAULT;
        if (prefs != null) {
            try { value = prefs.getInt(KEY, DEFAULT); } catch (Throwable ignored) {}
        }
        value = clamp(value);
        cached = value;
        return value;
    }

    public static boolean setFromText(String text) {
        if (text == null) return false;
        try {
            String clean = text.trim();
            if (clean.length() == 0) return false;
            int value = Integer.parseInt(clean);
            if (value < MIN || value > MAX) return false;
            cached = value;
            SharedPreferences prefs = prefs();
            if (prefs != null) prefs.edit().putInt(KEY, value).apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String label() {
        return String.valueOf(get());
    }

    /** Called at TikTok's final cache-count write. */
    public static void setCacheCount(
            com.ss.android.ugc.aweme.offlinemode.viewmodel.OfflineModeManagerVM manager,
            int original) {
        if (manager == null) return;
        int value = get();
        manager.B83(value > 0 ? value : original);
    }

    private static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    private static SharedPreferences prefs() {
        try {
            Context context = Margy.context();
            return context == null ? null : context.getSharedPreferences(
                    Margy.PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
