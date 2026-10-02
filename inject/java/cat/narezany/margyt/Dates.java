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
 * questions about where the video was opened from, each of them "is this
 * context one of the profile ones", and draws the date when they say yes. So
 * the mod answers those questions instead, and answers yes.
 *
 * The five of them are TikTok's own and renamed every release. They are named
 * in `margyt/dexpatch.py` rather than here, and the build reports how many
 * call sites each one matched -- a release that renames them shows up as a
 * row of zeroes rather than as a feature that quietly stopped working.
 */
public final class Dates {

    private Dates() {}

    public static final String KEY = "always_date";

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
    }

    // ------------------------------------------------- where the calls land

    /**
     * Four of the five are asked with a "not" in front of them.
     *
     * The screen's condition reads, in effect: draw the line when this is not
     * one of these contexts, and not one of those, and not that other one --
     * and, inside it, draw the date when it *is* the first one. So answering
     * yes to all five, which is what this did at first, does not turn the date
     * on: it fails the outer condition and the whole line goes.
     *
     * So the answers differ: yes to the one that is asked plainly, no to the
     * four that are asked negatively. Between them that is the same thing the
     * app works out when a video really was opened from a profile.
     */
    public static boolean fromProfile(String where) {
        return yes("LIZ", where);
    }

    public static boolean fromProfileToo(String where) {
        return no("LIZIZ", where);
    }

    public static boolean fromProfileAlso(String where) {
        return no("LIZJ", where);
    }

    public static boolean fromProfileAsWell(String where) {
        return no("LIZLLL", where);
    }

    public static boolean fromProfileOrOther(String where) {
        return no("LJFF", where);
    }

    private static boolean yes(String which, String where) {
        return isEnabled() || theirs(which, where);
    }

    private static boolean no(String which, String where) {
        return !isEnabled() && theirs(which, where);
    }

    /**
     * TikTok's own answer.
     *
     * Asked whenever the setting is off, because these questions are asked for
     * more than the date -- what the line says, what it links to -- and
     * answering one when nobody asked for the feature would change things
     * nobody asked to change.
     */
    private static boolean theirs(String which, String where) {
        try {
            Method method = found(which);
            if (method == null) return false;
            Object said = method.invoke(null, where);
            return said instanceof Boolean && ((Boolean) said).booleanValue();
        } catch (Throwable error) {
            Diary.note("date: " + error);
            return false;
        }
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
