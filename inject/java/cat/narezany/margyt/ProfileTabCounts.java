package cat.narezany.margyt;

import android.view.View;
import android.widget.TextView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Displays TikTok's own likes/reposts totals in the profile tab's number slot. */
public final class ProfileTabCounts {

    private static final int VIDEO_NUMBER_ID = 2131401988;

    private ProfileTabCounts() {}

    /** Replacement for the profile-tab callback; the original callback still runs first. */
    public static void Yc0(Object business, Object tab, int index, View view) {
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

        int count = readInt(user, likes ? "getFavoritingCount" : "getRepostCount", 0);
        setNumber(view, formatCount(count));
    }

    private static void invokeOriginalTabCallback(Object business, Object tab,
                                                   int index, View view) {
        try {
            for (Method callback : business.getClass().getMethods()) {
                Class<?>[] parameters = callback.getParameterTypes();
                if ("Yc0".equals(callback.getName()) && parameters.length == 3
                        && parameters[1] == int.class
                        && View.class.isAssignableFrom(parameters[2])) {
                    callback.setAccessible(true);
                    callback.invoke(business, tab, index, view);
                    return;
                }
            }
            throw new NoSuchMethodException("Yc0(tab, int, View)");
        } catch (InvocationTargetException error) {
            throw unchecked(error.getCause());
        } catch (Throwable error) {
            throw unchecked(error);
        }
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
            // TikTok's compact-number formatter is also used for the publication-tab count.
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
}
