package cat.narezany.margyt;

import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Displays TikTok's own likes/reposts totals in the profile tab's numeric badge. */
public final class ProfileTabCounts {

    // C71610Oao (the normal profile icon-tab view) binds its TuxAlertBadgeLayout here.
    private static final int TAB_BADGE_ID = 2131363244;
    private static final int BADGE_MAX_COUNT = 999999;

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
        setNumericBadge(view, count);
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

    private static void setNumericBadge(final View tabView, final int count) {
        if (tabView == null) return;
        tabView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object badge = tabView.findViewById(TAB_BADGE_ID);
                    if (badge == null) {
                        // C71610Oao keeps the same layout in this public field.
                        try {
                            badge = tabView.getClass().getField("LLJJIJIL").get(tabView);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (badge == null) return;

                    invokeInt(badge, "setVariant", 1); // Tux numeric-count variant
                    invokeInt(badge, "setMaxCount", BADGE_MAX_COUNT);
                    invokeInt(badge, "setCount", Math.max(0, count));
                    invokeNoArg(badge, "LIZJ"); // ProfileTabBaseBusiness hides it first.
                } catch (Throwable ignored) {
                    // Keep TikTok's tab usable if a future release changes its badge view.
                }
            }
        });
    }

    private static void invokeInt(Object receiver, String methodName, int value) throws Exception {
        Method method = receiver.getClass().getMethod(methodName, int.class);
        method.setAccessible(true);
        method.invoke(receiver, value);
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
