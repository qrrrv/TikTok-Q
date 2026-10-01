package cat.narezany.margyt;

import android.content.SharedPreferences;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;

import java.util.ArrayList;
import java.util.List;

/**
 * The advertisements, taken out of the page before anything sees it.
 *
 * TikTok's feed arrives as a `FeedItemList`, and every post in it says for
 * itself whether it is an advertisement -- `Aweme.isAd()`, a real name in a
 * real model. So the ads are dropped where the page is read rather than hidden
 * on each screen that might draw one: `FeedItemList.getItems()` is rewritten
 * to come through here, and what comes back is the same list without them.
 *
 * Answering `isAd()` with false instead would be the wrong lever. The post
 * would still be in the feed; it would simply stop being labelled as an
 * advertisement, which is worse than leaving it alone.
 *
 * Whatever cannot be understood is passed through untouched. A feed with no
 * ads is a preference; a feed that does not load is a broken app.
 */
public final class Feed {

    private Feed() {}

    public static final String KEY = "hide_ads";
    public static final String KEY_LIVE = "hide_live";
    public static final String KEY_PHOTOS = "hide_photos";

    private static volatile Boolean cached;

    public static boolean isEnabled() {
        Boolean known = cached;
        if (known != null) return known;
        SharedPreferences prefs = prefs();
        if (prefs == null) return false;  // too early to know; do not cache it
        boolean on = false;
        try {
            on = prefs.getBoolean(KEY, false);
        } catch (Throwable ignored) {
        }
        cached = on;
        return on;
    }

    public static void setEnabled(boolean enabled) {
        cached = enabled;
        SharedPreferences prefs = prefs();
        if (prefs != null) prefs.edit().putBoolean(KEY, enabled).apply();
    }

    // --------------------------------------------- what else to leave out

    public static boolean hides(String key) {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(key, false);
    }

    public static void setHides(String key, boolean on) {
        SharedPreferences prefs = prefs();
        if (prefs != null) prefs.edit().putBoolean(key, on).apply();
    }

    /** TikTok's own number for a live room in the feed. */
    private static final int LIVE = 101;

    private static boolean isLive(Aweme post) {
        try {
            if (post.getAwemeType() == LIVE) return true;
            if (post.getLiveId() != 0) return true;
            return post.getRoomFeedCellStruct() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPhotos(Aweme post) {
        try {
            return post.getPhotoModeImageInfo() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Your own post, which is never hidden from you.
     *
     * These filters are for recommendations, but every list of posts goes
     * through the same place, including your own profile. Turn off photo
     * posts and your own vanish from your own page.
     */
    private static boolean mine(Aweme post) {
        try {
            String me = Account.id();
            if (me == null || me.length() == 0) return false;
            return me.equals(post.getAuthorUid());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether this post is one the person asked not to see. */
    private static boolean unwanted(Object item) {
        if (!(item instanceof Aweme)) return false;
        Aweme post = (Aweme) item;
        if (mine(post)) return false;
        // a patch gets the first word: an ad TikTok started marking some other
        // way is exactly the sort of thing that should not need a new apk
        Object mended = Patch.ask("feed", post);
        if (mended instanceof Boolean) return ((Boolean) mended).booleanValue();
        try {
            if (isEnabled() && post.isAd()) return true;
            if (hides(KEY_LIVE) && isLive(post)) return true;
            if (isLive(post)) return false;   // a room is neither a photo nor a video
            if (hides(KEY_PHOTOS) && isPhotos(post)) return true;
            if (Tags.blocks(post)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static SharedPreferences prefs() {
        try {
            android.content.Context context = Margy.context();
            if (context == null) return null;
            return context.getSharedPreferences(Margy.PREFS, android.content.Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** How many have been dropped since the process started, for the diary. */
    private static int dropped;

    // ------------------------------------------------ where the call lands

    /**
     * Every `FeedItemList.getItems()` in TikTok's bytecode comes here first.
     *
     * The list is only rebuilt when there is something to leave out -- the
     * ordinary page comes back as the very object the app asked for, which
     * keeps this out of the way of everything that reads a feed and is not
     * looking for advertisements.
     */
    public static List getItems(FeedItemList page) {
        if (page == null) return null;
        return without(page.getItems());
    }

    /** A page of posts without the ones nobody asked to see. */
    private static List without(List items) {
        if (items == null) return items;
        // Observe before filters remove anything, so the counters describe the
        // feed TikTok delivered rather than only what the person chose to see.
        try {
            for (Object item : items) {
                if (item instanceof Aweme) Stats.observe(item);
            }
        } catch (Throwable error) {
            Diary.note("stats feed: " + error);
        }
        if (!isEnabled() && !hides(KEY_LIVE) && !hides(KEY_PHOTOS)
                && Tags.all().isEmpty() && Patch.running().length() == 0) {
            return Plugins.feed(items);
        }
        try {
            int out = 0;
            for (Object item : items) {
                if (unwanted(item)) out++;
            }
            if (out == 0) return Plugins.feed(items);

            List kept = new ArrayList(items.size() - out);
            for (Object item : items) {
                if (unwanted(item)) continue;
                kept.add(item);
            }
            dropped += out;
            Diary.note("feed: " + out + " left out, " + dropped + " so far");
            return Plugins.feed(kept);
        } catch (Throwable error) {
            Diary.note("feed: leaving the page alone, " + error);
            return items;
        }
    }
}
