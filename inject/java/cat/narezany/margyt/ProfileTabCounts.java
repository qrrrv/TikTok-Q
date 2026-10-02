package cat.narezany.margyt;

import X.C71540Oah;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Displays TikTok's own likes/reposts totals in the profile tab's number slot. */
public final class ProfileTabCounts {

    private static final int VIDEO_NUMBER_ID = 2131401988;
    private static final List<Binding> BINDINGS = new ArrayList<>();

    private ProfileTabCounts() {}

    /** Replacement for the profile-tab callback; the original callback still runs first. */
    public static void Yc0(Object business, C71540Oah tab, int index, View view) {
        if (business == null) return;
        invokeOriginalTabCallback(business, tab, index, view);

        String name = stringValue(invokeNoArg(business, "getName"));
        final boolean likes;
        if ("profile_tab_like".equals(name)) {
            likes = true;
        } else if ("profile_tab_repost".equals(name)) {
            likes = false;
        } else {
            return;
        }

        Object pager = invokeNoArg(business, "LIZ");
        Object user = invokeNoArg(pager, "getUser");
        if (user == null || view == null) return;

        String uid = stringValue(invokeNoArg(user, "getUid"));
        int count = readInt(user, likes ? "getFavoritingCount" : "getRepostCount", 0);
        remember(view, uid, likes);
        setNumber(view, formatCount(count));
    }

    /** Return the unmodified TikTok value while refreshing an already-bound badge. */
    public static int getFavoritingCount(Object user) {
        int count = readInt(user, "getFavoritingCount", 0);
        String uid = stringValue(invokeNoArg(user, "getUid"));
        refresh(uid, true, count);
        refresh(uid, false, readInt(user, "getRepostCount", 0));
        return count;
    }

    /** Return the unmodified TikTok value while refreshing an already-bound badge. */
    public static int getRepostCount(Object user) {
        int count = readInt(user, "getRepostCount", 0);
        String uid = stringValue(invokeNoArg(user, "getUid"));
        refresh(uid, false, count);
        refresh(uid, true, readInt(user, "getFavoritingCount", 0));
        return count;
    }

    private static void invokeOriginalTabCallback(Object business, C71540Oah tab,
                                                   int index, View view) {
        try {
            Method callback = business.getClass().getMethod(
                    "Yc0", C71540Oah.class, int.class, View.class);
            callback.setAccessible(true);
            callback.invoke(business, tab, index, view);
        } catch (InvocationTargetException error) {
            throw unchecked(error.getCause());
        } catch (Throwable error) {
            throw unchecked(error);
        }
    }

    private static void remember(View view, String uid, boolean likes) {
        synchronized (BINDINGS) {
            for (Iterator<Binding> it = BINDINGS.iterator(); it.hasNext();) {
                Binding binding = it.next();
                View bound = binding.view.get();
                if (bound == null) {
                    it.remove();
                } else if (bound == view && binding.likes == likes) {
                    it.remove();
                }
            }
            BINDINGS.add(new Binding(view, uid, likes));
        }
    }

    private static void refresh(String uid, boolean likes, int count) {
        if (uid == null) return;
        final String text = formatCount(count);
        List<View> targets = new ArrayList<>();
        synchronized (BINDINGS) {
            for (Iterator<Binding> it = BINDINGS.iterator(); it.hasNext();) {
                Binding binding = it.next();
                View view = binding.view.get();
                if (view == null) {
                    it.remove();
                } else if (binding.likes == likes && uid.equals(binding.uid)) {
                    targets.add(view);
                }
            }
        }
        for (View view : targets) setNumber(view, text);
    }

    private static void setNumber(final View view, final String text) {
        if (view == null) return;
        view.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Method setter = view.getClass().getMethod("setVideoNumber", String.class);
                    setter.setAccessible(true);
                    setter.invoke(view, text);
                    return;
                } catch (Throwable ignored) {
                    // Some TikTok tab layouts expose the same number slot only as a child view.
                }
                try {
                    View number = view.findViewById(VIDEO_NUMBER_ID);
                    if (number instanceof TextView) {
                        number.setVisibility(View.VISIBLE);
                        Badge.setText((TextView) number, text);
                    }
                } catch (Throwable ignored) {
                    // A future app layout without a number slot should remain otherwise usable.
                }
            }
        });
    }

    private static String formatCount(int count) {
        if (count < 0) count = 0;
        try {
            // This is TikTok's own compact-number formatter, also used by the
            // profile's publication-tab count. Fall back if a later release renames it.
            Class<?> formatter = Class.forName("X.0EYv");
            Method method = formatter.getDeclaredMethod("LIZIZ", int.class);
            method.setAccessible(true);
            Object value = method.invoke(null, count);
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {
        }
        return String.valueOf(count);
    }

    private static int readInt(Object receiver, String methodName, int fallback) {
        Object value = invokeNoArg(receiver, methodName);
        if (value instanceof Number) return ((Number) value).intValue();
        String fieldName = "getFavoritingCount".equals(methodName)
                ? "favoritingCount" : "repostCount";
        try {
            Object fieldValue = receiver.getClass().getField(fieldName).get(receiver);
            if (fieldValue instanceof Number) return ((Number) fieldValue).intValue();
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static Object invokeNoArg(Object receiver, String methodName) {
        if (receiver == null) return null;
        try {
            Method method = receiver.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static RuntimeException unchecked(Throwable error) {
        if (error instanceof RuntimeException) return (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        return new RuntimeException(error);
    }

    private static final class Binding {
        final WeakReference<View> view;
        final String uid;
        final boolean likes;

        Binding(View view, String uid, boolean likes) {
            this.view = new WeakReference<>(view);
            this.uid = uid;
            this.likes = likes;
        }
    }
}
