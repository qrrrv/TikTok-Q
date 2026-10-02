package cat.narezany.margyt;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;

/**
 * The date on a video, everywhere rather than only on a profile.
 *
 * TikTok already writes it beside the author's name -- but only when the video
 * was opened from somebody's profile. In the feed, in search, in a hashtag,
 * the same line is drawn without it.
 *
 * What decides is not a setting and not a flag: the screen asks a handful of
 * questions about where the video was opened from, and draws the date when the
 * answers add up to "a profile". So the mod answers those questions instead.
 *
 * The five of them are TikTok's own and renamed every release. They are named
 * in `margyt/dexpatch.py` rather than here, and the build reports how many
 * call sites each one matched and where it found them -- a release that
 * renames them shows up in the build log rather than as a feature that
 * quietly stopped working.
 *
 * HOW THE ANSWERS ARE CHOSEN
 *
 * Which of the five is asked plainly and which with a "not" in front of it is
 * TikTok's business, and guessing it is what kept the date from showing: a
 * wrong guess on any one of them and the whole line goes. So nothing is
 * guessed. When the feature is on, each question is put to TikTok's own code
 * again, with the place a profile opens from in place of the place this video
 * really came from -- and whatever it answers is the answer. That is exactly
 * what the app would say for a video opened from a profile, whichever way
 * round each question is asked.
 *
 * The place a profile opens from is found, not written down: a short list of
 * the names TikTok uses is tried against the first question (the one that
 * asks "is this a profile" and says yes to one), and failing that the mod
 * learns it from the first time the app itself says yes. If neither works
 * the old fixed answers are used, so nothing gets worse than it was.
 */
public final class Dates {

    private Dates() {}

    public static final String KEY = "always_date";
    private static final String KEY_CONTEXT = "always_date_context";

    /** Where a profile opens from, as TikTok says it. Others' profiles first. */
    private static final String[] PROFILES = {"others_homepage", "personal_homepage"};

    private static final String[] GATES = {"LIZ", "LIZIZ", "LIZJ", "LIZLLL", "LJFF"};

    private static volatile Boolean on;

    public static boolean isEnabled() {
        Boolean known = on;
        if (known != null) return known.booleanValue();
        SharedPreferences prefs = prefs();
        if (prefs == null) return false;
        boolean value = prefs.getBoolean(KEY, false);
        on = Boolean.valueOf(value);
        return value;
    }

    public static void setEnabled(boolean enabled) {
        on = Boolean.valueOf(enabled);
        SharedPreferences prefs = prefs();
        if (prefs != null) prefs.edit().putBoolean(KEY, enabled).apply();
        Diary.note("date: " + (enabled ? "on" : "off"));
    }

    // ------------------------------------------------- where the calls land

    public static boolean fromProfile(String where) {
        if (asking.get() == null) learn(where);
        return answer("LIZ", where, true);
    }

    public static boolean fromProfileToo(String where) {
        return answer("LIZIZ", where, false);
    }

    public static boolean fromProfileAlso(String where) {
        return answer("LIZJ", where, false);
    }

    public static boolean fromProfileAsWell(String where) {
        return answer("LIZLLL", where, false);
    }

    public static boolean fromProfileOrOther(String where) {
        return answer("LJFF", where, false);
    }

    /**
     * One question, answered.
     *
     * Off: TikTok's own answer, untouched -- these questions are asked for more
     * than the date (what the line says, what it links to) and nobody who left
     * the feature off asked to change any of that.
     *
     * On: the answer for a video opened from a profile, or, if that cannot be
     * worked out, the fixed answer that was used before: yes to the first,
     * no to the other four.
     */
    private static boolean answer(String which, String where, boolean fixed) {
        // One gate may call another inside TikTok, and after the patch that call
        // comes back through here. While the mod is asking TikTok a question,
        // everything under that question is TikTok's own, or the answer it gets
        // back is a mixture of TikTok's and the mod's.
        if (asking.get() != null) return theirs(which, where);
        reached(which);
        if (!isEnabled()) return theirs(which, where);
        Boolean said = asProfile(which);
        return said != null ? said.booleanValue() : fixed;
    }

    // ------------------------------------------------ a profile, pretended

    /** The place a profile opens from. Null until it is known. */
    private static volatile String profile;
    /** What the app itself last said was a profile. */
    private static volatile String learned;
    /** One answer per question, for the context above; the questions are pure. */
    private static final java.util.Map<String, Boolean> answers =
            new java.util.HashMap<String, Boolean>();
    private static long retryAt;

    private static Boolean asProfile(String which) {
        String place = place();
        if (place == null) return null;
        synchronized (answers) {
            if (answers.containsKey(which)) return answers.get(which);
            Boolean said = ask(which, place);
            answers.put(which, said);
            return said;
        }
    }

    /** Where a profile opens from, worked out once and then remembered. */
    private static String place() {
        String known = profile;
        if (known != null) return known;
        synchronized (answers) {
            if (profile != null) return profile;
            long now = android.os.SystemClock.uptimeMillis();
            if (now < retryAt) return null;
            retryAt = now + 4000;

            String found = null;
            String saved = saved();
            if (saved != null && Boolean.TRUE.equals(ask("LIZ", saved))) found = saved;
            for (int i = 0; found == null && i < PROFILES.length; i++) {
                if (Boolean.TRUE.equals(ask("LIZ", PROFILES[i]))) found = PROFILES[i];
            }
            if (found == null && learned != null) found = learned;
            if (found == null) return null;

            answers.clear();
            profile = found;
            StringBuilder line = new StringBuilder("date: a profile is '" + found + "' --");
            for (String gate : GATES) {
                Boolean said = ask(gate, found);
                answers.put(gate, said);
                line.append(' ').append(gate).append('=').append(said);
            }
            Diary.note(line.toString());
            save(found);
            return found;
        }
    }

    /**
     * The first question is the plain one -- "is this a profile" -- so the
     * first time the app itself says yes to it, what it was asked about is a
     * profile. Only needed when none of the usual names is.
     */
    private static void learn(String where) {
        if (profile != null || learned != null) return;
        if (where == null || where.length() == 0) return;
        if (Boolean.TRUE.equals(ask("LIZ", where))) {
            learned = where;
            Diary.note("date: the app opened a profile as '" + where + "'");
        }
    }

    private static String saved() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return null;
        try {
            String value = prefs.getString(KEY_CONTEXT, null);
            return value == null || value.length() == 0 ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void save(String value) {
        SharedPreferences prefs = prefs();
        if (prefs != null) prefs.edit().putString(KEY_CONTEXT, value).apply();
    }

    /** Whether the first question has ever been asked, so the diary can say. */
    private static boolean told;

    private static void reached(String which) {
        if (told) return;
        told = true;
        Diary.note("date: the app asks the gates (first " + which + ")");
    }

    // ------------------------------------------------------ TikTok's answer

    /** Set while the mod is in the middle of asking TikTok a question. */
    private static final ThreadLocal<Boolean> asking = new ThreadLocal<Boolean>();

    /** TikTok's own answer, or null if it could not be asked. */
    private static Boolean ask(String which, String where) {
        boolean outer = asking.get() != null;
        try {
            Method method = found(which);
            if (method == null) return null;
            asking.set(Boolean.TRUE);
            Object said = method.invoke(null, where);
            return said instanceof Boolean ? (Boolean) said : null;
        } catch (Throwable error) {
            Diary.note("date: " + which + " -- " + error);
            return null;
        } finally {
            if (!outer) asking.remove();
        }
    }

    private static boolean theirs(String which, String where) {
        Boolean said = ask(which, where);
        return said != null && said.booleanValue();
    }

    private static final java.util.Map<String, Method> known =
            new java.util.HashMap<String, Method>();

    private static Method found(String which) {
        synchronized (known) {
            if (known.containsKey(which)) return known.get(which);
            Method theirs = null;
            try {
                theirs = Class.forName(owner(which)).getDeclaredMethod(method(which), String.class);
                theirs.setAccessible(true);
            } catch (Throwable error) {
                Diary.note("date: " + which + " -- " + error);
            }
            known.put(which, theirs);
            return theirs;
        }
    }

    private static String owner(String which) {
        if ("LIZ".equals(which)) return Anchors.DATE_GATE_LIZ;
        if ("LIZIZ".equals(which)) return Anchors.DATE_GATE_LIZIZ;
        if ("LIZJ".equals(which)) return Anchors.DATE_GATE_LIZJ;
        if ("LIZLLL".equals(which)) return Anchors.DATE_GATE_LIZLLL;
        return Anchors.DATE_GATE_LJFF;
    }

    private static String method(String which) {
        if ("LIZ".equals(which)) return Anchors.DATE_GATE_LIZ_METHOD;
        if ("LIZIZ".equals(which)) return Anchors.DATE_GATE_LIZIZ_METHOD;
        if ("LIZJ".equals(which)) return Anchors.DATE_GATE_LIZJ_METHOD;
        if ("LIZLLL".equals(which)) return Anchors.DATE_GATE_LIZLLL_METHOD;
        return Anchors.DATE_GATE_LJFF_METHOD;
    }

    private static SharedPreferences prefs() {
        Context context = Margy.context();
        if (context == null) return null;
        return context.getSharedPreferences(Margy.PREFS, Context.MODE_PRIVATE);
    }
}
