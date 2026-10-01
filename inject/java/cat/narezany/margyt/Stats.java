package cat.narezany.margyt;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Local, privacy-first counters for posts observed in TikTok's feed.
 *
 * TikTok does not expose an official aggregate endpoint to this patcher. We
 * therefore count unique post ids seen in the feed and use reflection for the
 * optional like/repost flags present in different TikTok releases. Nothing is
 * uploaded; all data stays in the mod's private preferences.
 */
public final class Stats {
    private Stats() {}
    private static final String PREFS = "tiktokq_stats";
    private static final String VIDEOS = "videos";
    private static final String LIKES = "likes";
    private static final String REPOSTS = "reposts";
    private static final String VIDEO_COUNT = "video_count";
    private static final String LIKE_COUNT = "like_count";
    private static final String REPOST_COUNT = "repost_count";
    private static final int MAX_IDS = 10000;

    /** Called from the feed interception point; deliberately cheap and safe. */
    public static void observe(Object post) {
        if (post == null) return;
        try {
            String id = id(post);
            if (id == null || id.length() == 0) return;
            SharedPreferences p = prefs();
            if (p == null) return;
            Set<String> videos = ids(p, VIDEOS);
            if (videos.add(id)) {
                trim(videos);
                save(p, VIDEOS, videos, VIDEO_COUNT);
            }
            if (truth(post, "isUserDigg", "isLiked", "isLike", "isDigged")) {
                Set<String> likes = ids(p, LIKES);
                if (likes.add(id)) {
                    trim(likes);
                    save(p, LIKES, likes, LIKE_COUNT);
                }
            }
            if (truth(post, "isReposted", "isRepost", "getIsRepost", "isUserRepost")) {
                Set<String> reposts = ids(p, REPOSTS);
                if (reposts.add(id)) {
                    trim(reposts);
                    save(p, REPOSTS, reposts, REPOST_COUNT);
                }
            }
        } catch (Throwable error) {
            // Statistics must never affect rendering or feed loading.
            Diary.note("stats: " + error);
        }
    }

    public static int videos() { return number(VIDEO_COUNT); }
    public static int likes() { return number(LIKE_COUNT); }
    public static int reposts() { return number(REPOST_COUNT); }

    public static void reset() {
        SharedPreferences p = prefs();
        if (p != null) p.edit().clear().apply();
    }

    private static SharedPreferences prefs() {
        Context context = Margy.context();
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static int number(String key) {
        SharedPreferences p = prefs();
        return p == null ? 0 : p.getInt(key, 0);
    }
    private static Set<String> ids(SharedPreferences p, String key) {
        return new HashSet<String>(p.getStringSet(key, new HashSet<String>()));
    }
    private static void save(SharedPreferences p, String idsKey, Set<String> values, String countKey) {
        p.edit().putStringSet(idsKey, values).putInt(countKey, values.size()).apply();
    }
    private static void trim(Set<String> values) {
        while (values.size() > MAX_IDS) values.remove(values.iterator().next());
    }
    private static String id(Object object) {
        String[] methods = {"getAid", "getAwemeId", "getAwemeID", "getItemId", "getId"};
        for (String name : methods) {
            Object value = call(object, name);
            if (value != null && String.valueOf(value).length() > 0) return String.valueOf(value);
        }
        String[] fields = {"awemeId", "aweme_id", "id"};
        for (String name : fields) {
            try {
                Field field = object.getClass().getField(name);
                Object value = field.get(object);
                if (value != null && String.valueOf(value).length() > 0) return String.valueOf(value);
            } catch (Throwable ignored) {}
        }
        return null;
    }
    private static boolean truth(Object object, String... names) {
        for (String name : names) {
            Object value = call(object, name);
            if (value instanceof Boolean) return ((Boolean) value).booleanValue();
            if (value instanceof Number) return ((Number) value).intValue() != 0;
        }
        return false;
    }
    private static Object call(Object object, String name) {
        try {
            Method method = object.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
