package cat.narezany.margyt;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local, privacy-first counters for the posts TikTok hands to the screen.
 *
 * Nothing is uploaded; every number stays in the mod's private preferences.
 *
 * WHY THE FIRST VERSION COUNTED SO LITTLE
 *
 * It looked at a post once, at the moment the page arrived, and asked it
 * whether it was liked. A post that arrives unliked and is liked a second
 * later -- which is how every like happens -- was never looked at again, so
 * the counter only ever moved for posts that were already liked when they
 * came. And the questions it asked had names that TikTok's model does not use.
 *
 * WHAT IT DOES NOW
 *
 *  - Every post that passes is remembered by reference, weakly, so the model
 *    object TikTok keeps for the screen is the one that gets asked. When the
 *    person likes or reposts, TikTok changes that object; the next look sees
 *    it. A short timer takes that look every couple of seconds, and the
 *    settings screen takes one more right before it draws.
 *  - The flag is found on the model rather than assumed: a list of the names
 *    it is known by is tried first, then any method or field whose name says
 *    "digg" (TikTok's word for a like) or "repost". What was chosen is written
 *    to the diary, and when nothing fits, so are the names that came close.
 *  - Counting follows the state: a post that turns liked is added, a post that
 *    turns unliked is taken away again, so the number is the number of posts
 *    that are liked now, not the number of times a button was pressed.
 *  - The totals are kept apart from the ids behind them, so the ids can be a
 *    bounded window without the totals stopping at the bound.
 *  - Saving is held back and done in one piece, because the feed is no place
 *    to rewrite ten thousand ids on every post.
 */
public final class Stats {
    private Stats() {}

    private static final String PREFS = "tiktokq_stats";

    // The totals keep the keys they always had, so the numbers survive the update.
    private static final String VIDEO_COUNT = "video_count";
    private static final String LIKE_COUNT = "like_count";
    private static final String REPOST_COUNT = "repost_count";
    // The ids behind them used to be string sets under these keys ...
    private static final String OLD_VIDEOS = "videos";
    private static final String OLD_LIKES = "likes";
    private static final String OLD_REPOSTS = "reposts";
    // ... and are one comma-separated string each now, which is far cheaper to write.
    private static final String VIDEOS = "videos_v2";
    private static final String LIKES = "likes_v2";
    private static final String REPOSTS = "reposts_v2";

    /** Ids kept per list, to tell a post seen again from a new one. */
    private static final int MAX_IDS = 3000;
    /** Posts watched for a change at once. */
    private static final int MAX_TRACKED = 400;
    private static final long POLL_MS = 2000;
    private static final long SAVE_MS = 3000;

    private static final Object LOCK = new Object();

    private static boolean loaded;
    private static int videoTotal;
    private static int likeTotal;
    private static int repostTotal;
    private static final Set<String> seen = new LinkedHashSet<String>();
    private static final Set<String> liked = new LinkedHashSet<String>();
    private static final Set<String> reposted = new LinkedHashSet<String>();

    /** The posts being watched, newest use last; the oldest goes first. */
    private static final Map<String, WeakReference<Object>> tracked =
            new LinkedHashMap<String, WeakReference<Object>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, WeakReference<Object>> eldest) {
                    return size() > MAX_TRACKED;
                }
            };

    // ------------------------------------------------------------ the API

    /** Called wherever a post passes by; deliberately cheap and safe. */
    public static void observe(Object post) {
        if (post == null) return;
        try {
            String id = id(post);
            if (id == null) return;
            synchronized (LOCK) {
                if (!load()) return;
                if (seen.add(id)) {
                    videoTotal++;
                    trim(seen);
                    changed();
                }
                WeakReference<Object> held = tracked.get(id);
                if (held == null || held.get() != post) {
                    tracked.put(id, new WeakReference<Object>(post));
                }
                look(id, post);
            }
            watch();
        } catch (Throwable error) {
            // Statistics must never affect rendering or feed loading.
            Diary.note("stats: " + error);
        }
    }

    public static int videos() {
        refresh();
        synchronized (LOCK) {
            load();
            return videoTotal;
        }
    }

    public static int likes() {
        refresh();
        synchronized (LOCK) {
            load();
            return likeTotal;
        }
    }

    public static int reposts() {
        refresh();
        synchronized (LOCK) {
            load();
            return repostTotal;
        }
    }

    /** Look at every watched post now, and save what that changed. */
    public static void refresh() {
        try {
            poll();
            flush();
        } catch (Throwable error) {
            Diary.note("stats refresh: " + error);
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            load();
            seen.clear();
            liked.clear();
            reposted.clear();
            tracked.clear();
            videoTotal = 0;
            likeTotal = 0;
            repostTotal = 0;
            dirty = false;
            SharedPreferences p = prefs();
            if (p != null) p.edit().clear().apply();
        }
    }

    // ------------------------------------------------------ looking at a post

    /** Read what a post says about being liked and reposted, and count the change. */
    private static void look(String id, Object post) {
        Probe probe = probe(post.getClass());
        if (probe.like != null) {
            Boolean now = probe.like.read(post);
            if (now != null) {
                if (now.booleanValue()) {
                    if (liked.add(id)) {
                        likeTotal++;
                        trim(liked);
                        changed();
                    }
                } else if (liked.remove(id)) {
                    likeTotal = Math.max(0, likeTotal - 1);
                    changed();
                }
            }
        }
        if (probe.repost != null) {
            Boolean now = probe.repost.read(post);
            if (now != null) {
                if (now.booleanValue()) {
                    if (reposted.add(id)) {
                        repostTotal++;
                        trim(reposted);
                        changed();
                    }
                } else if (reposted.remove(id)) {
                    repostTotal = Math.max(0, repostTotal - 1);
                    changed();
                }
            }
        }
    }

    // ------------------------------------------------------------- the timer

    private static Handler handler;
    private static boolean watching;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            boolean again = poll();
            synchronized (LOCK) {
                watching = again;
            }
            if (again) handler().postDelayed(this, POLL_MS);
        }
    };

    private static final Runnable SAVE = new Runnable() {
        @Override
        public void run() {
            flush();
        }
    };

    private static Handler handler() {
        synchronized (LOCK) {
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            return handler;
        }
    }

    /** Start the timer if it is not running; it stops by itself when nothing is left. */
    private static void watch() {
        synchronized (LOCK) {
            if (watching) return;
            watching = true;
        }
        handler().postDelayed(TICK, POLL_MS);
    }

    /** One look at everything watched. Returns whether there is anything left to watch. */
    private static boolean poll() {
        try {
            synchronized (LOCK) {
                if (!loaded || tracked.isEmpty()) return false;
                Iterator<Map.Entry<String, WeakReference<Object>>> it =
                        tracked.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, WeakReference<Object>> entry = it.next();
                    Object post = entry.getValue().get();
                    if (post == null) {
                        it.remove();
                        continue;
                    }
                    look(entry.getKey(), post);
                }
                return !tracked.isEmpty();
            }
        } catch (Throwable error) {
            Diary.note("stats poll: " + error);
            return true;
        }
    }

    // ---------------------------------------------------------------- saving

    private static boolean dirty;
    private static boolean savePosted;

    /** Something changed: save it in a moment, once, however many changes come. */
    private static void changed() {
        dirty = true;
        if (savePosted) return;
        savePosted = true;
        handler().postDelayed(SAVE, SAVE_MS);
    }

    private static void flush() {
        synchronized (LOCK) {
            savePosted = false;
            if (!dirty || !loaded) return;
            SharedPreferences p = prefs();
            if (p == null) return;
            dirty = false;
            p.edit()
                    .putInt(VIDEO_COUNT, videoTotal)
                    .putInt(LIKE_COUNT, likeTotal)
                    .putInt(REPOST_COUNT, repostTotal)
                    .putString(VIDEOS, join(seen))
                    .putString(LIKES, join(liked))
                    .putString(REPOSTS, join(reposted))
                    .remove(OLD_VIDEOS)
                    .remove(OLD_LIKES)
                    .remove(OLD_REPOSTS)
                    .apply();
        }
    }

    private static boolean load() {
        if (loaded) return true;
        SharedPreferences p = prefs();
        if (p == null) return false;
        try {
            videoTotal = p.getInt(VIDEO_COUNT, 0);
            likeTotal = p.getInt(LIKE_COUNT, 0);
            repostTotal = p.getInt(REPOST_COUNT, 0);
            fill(seen, p, VIDEOS, OLD_VIDEOS);
            fill(liked, p, LIKES, OLD_LIKES);
            fill(reposted, p, REPOSTS, OLD_REPOSTS);
            videoTotal = Math.max(videoTotal, seen.size());
            likeTotal = Math.max(likeTotal, liked.size());
            repostTotal = Math.max(repostTotal, reposted.size());
            trim(seen);
            trim(liked);
            trim(reposted);
        } catch (Throwable error) {
            Diary.note("stats load: " + error);
        }
        loaded = true;
        return true;
    }

    private static void fill(Set<String> into, SharedPreferences p, String key, String oldKey) {
        String csv = null;
        try {
            csv = p.getString(key, null);
        } catch (Throwable ignored) {
        }
        if (csv != null) {
            for (String id : csv.split(",")) {
                if (id.length() > 0) into.add(id);
            }
            return;
        }
        try {
            Set<String> old = p.getStringSet(oldKey, null);
            if (old != null) into.addAll(old);
        } catch (Throwable ignored) {
        }
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder(values.size() * 20);
        for (String id : values) {
            if (out.length() > 0) out.append(',');
            out.append(id);
        }
        return out.toString();
    }

    private static void trim(Set<String> values) {
        Iterator<String> it = values.iterator();
        while (values.size() > MAX_IDS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static SharedPreferences prefs() {
        Context context = Margy.context();
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------- finding the flags on the model

    private static final String[] ID_METHODS =
            {"getAid", "getAwemeId", "getAwemeID", "getItemId", "getId"};
    private static final String[] ID_FIELDS = {"aid", "awemeId", "aweme_id", "id"};

    // TikTok calls a like a "digg", and a post you liked has `userDigged` set.
    private static final String[] LIKE_METHODS = {
            "getUserDigged", "isUserDigged", "getUserDigg", "isUserDigg", "isDigged",
            "getDigged", "isLiked", "getIsLiked", "isLike", "getUserLiked", "isUserLiked"};
    private static final String[] LIKE_FIELDS =
            {"userDigged", "isUserDigged", "user_digged", "isDigged", "isLiked"};
    private static final String[] REPOST_METHODS = {
            "isReposted", "getIsReposted", "isRepost", "getIsRepost", "isUserRepost",
            "isUserReposted", "getUserRepost", "getUserReposted", "hasReposted", "getReposted"};
    private static final String[] REPOST_FIELDS = {
            "isReposted", "reposted", "userReposted", "userRepost", "isUserRepost",
            "isRepost", "user_repost"};

    /** Words that make a name something other than "did I do it". */
    private static final String[] NOT_A_STATE = {
            "count", "num", "total", "type", "uid", "list", "enable", "disable", "allow",
            "show", "hide", "can", "anim", "icon", "text", "time", "tip", "label", "url",
            "info", "style", "position", "version", "dialog", "guide", "mode", "cover",
            "source", "from", "origin", "author", "status", "entrance", "permission"};

    /** How to read one flag off a post: a method, or failing that a field. */
    private static final class Reader {
        final Method method;
        final Field field;

        Reader(Method method, Field field) {
            this.method = method;
            this.field = field;
        }

        String name() {
            return method != null ? method.getName() + "()" : field.getName();
        }

        Boolean read(Object target) {
            try {
                Object value = method != null ? method.invoke(target) : field.get(target);
                if (value instanceof Boolean) return (Boolean) value;
                if (value instanceof Number) return Boolean.valueOf(((Number) value).longValue() > 0);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** What is known about one model class, worked out once. */
    private static final class Probe {
        final List<Method> ids = new ArrayList<Method>();
        final List<Field> idFields = new ArrayList<Field>();
        Reader like;
        Reader repost;
    }

    private static final Map<Class<?>, Probe> probes = new HashMap<Class<?>, Probe>();

    private static Probe probe(Class<?> type) {
        synchronized (probes) {
            Probe known = probes.get(type);
            if (known != null) return known;
            Probe made = new Probe();
            for (String name : ID_METHODS) {
                Method m = method(type, name);
                if (m != null) made.ids.add(m);
            }
            for (String name : ID_FIELDS) {
                Field f = field(type, name);
                if (f != null) made.idFields.add(f);
            }
            made.like = reader(type, LIKE_METHODS, LIKE_FIELDS, "digg");
            made.repost = reader(type, REPOST_METHODS, REPOST_FIELDS, "repost");
            probes.put(type, made);
            Diary.note("stats: likes <- " + (made.like == null ? "none found" : made.like.name())
                    + ", reposts <- " + (made.repost == null ? "none found" : made.repost.name()));
            if (made.like == null) Diary.note("stats: near misses for likes: " + near(type, "digg"));
            if (made.repost == null) Diary.note("stats: near misses for reposts: " + near(type, "repost"));
            return made;
        }
    }

    private static Reader reader(Class<?> type, String[] methods, String[] fields, String needle) {
        for (String name : methods) {
            Method m = method(type, name);
            if (m != null && isFlag(m.getReturnType())) return new Reader(m, null);
        }
        for (String name : fields) {
            Field f = field(type, name);
            if (f != null && isFlag(f.getType())) return new Reader(null, f);
        }
        // not under any name it is known by: anything that says it
        List<Method> found = new ArrayList<Method>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0 || Modifier.isStatic(m.getModifiers())) continue;
                if (!isFlag(m.getReturnType())) continue;
                if (!states(m.getName(), needle)) continue;
                found.add(m);
            }
        }
        if (!found.isEmpty()) {
            Collections.sort(found, new Comparator<Method>() {
                @Override
                public int compare(Method a, Method b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            Method m = found.get(0);
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
            }
            return new Reader(m, null);
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !isFlag(f.getType())) continue;
                if (!states(f.getName(), needle)) continue;
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return new Reader(null, f);
            }
        }
        return null;
    }

    /** Whether a name is one that says "the person did this" rather than "how many". */
    private static boolean states(String name, String needle) {
        String lower = name.toLowerCase(Locale.US);
        if (!lower.contains(needle)) return false;
        if (lower.endsWith("id") || lower.startsWith("set")) return false;
        for (String word : NOT_A_STATE) {
            if (lower.contains(word)) return false;
        }
        return true;
    }

    private static boolean isFlag(Class<?> type) {
        return type == boolean.class || type == Boolean.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == byte.class;
    }

    /** Every no-argument method and field whose name has the word in it, for the diary. */
    private static String near(Class<?> type, String needle) {
        List<String> names = new ArrayList<String>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0
                        && m.getName().toLowerCase(Locale.US).contains(needle)) {
                    names.add(m.getName() + ":" + m.getReturnType().getSimpleName());
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().toLowerCase(Locale.US).contains(needle)) {
                    names.add(f.getName() + ":" + f.getType().getSimpleName());
                }
            }
        }
        if (names.isEmpty()) return "nothing";
        Collections.sort(names);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size() && i < 10; i++) {
            if (i > 0) out.append(", ");
            out.append(names.get(i));
        }
        return out.toString();
    }

    private static Method method(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String id(Object post) {
        Probe probe = probe(post.getClass());
        for (Method m : probe.ids) {
            try {
                Object value = m.invoke(post);
                if (value != null && String.valueOf(value).length() > 0) {
                    String text = String.valueOf(value);
                    if (!"0".equals(text)) return text;
                }
            } catch (Throwable ignored) {
            }
        }
        for (Field f : probe.idFields) {
            try {
                Object value = f.get(post);
                if (value != null && String.valueOf(value).length() > 0) {
                    String text = String.valueOf(value);
                    if (!"0".equals(text)) return text;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
