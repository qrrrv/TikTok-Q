package cat.narezany.margyt;

import android.app.Activity;
import android.app.Application;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Re-skins TikTok's three bottom navigation icons without replacing the
 * navigation item or its click listener. TikTok's view names are obfuscated,
 * so the stable signals are the accessibility/resource label and the fact
 * that the view is an icon in the lower part of the window.
 */
public final class NavigationDesign implements Application.ActivityLifecycleCallbacks {
    private static final int HOME = 1;
    private static final int FRIENDS = 2;
    private static final int PROFILE = 3;
    private static final long QUIET_MS = 180L;
    private static NavigationDesign instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<View, Integer> styled = new WeakHashMap<View, Integer>();
    private final Map<View, ViewTreeObserver.OnGlobalLayoutListener> listeners =
            new WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>();
    private long lastPass;

    private NavigationDesign() {}

    public static void start(Application application) {
        if (application == null || instance != null) return;
        instance = new NavigationDesign();
        application.registerActivityLifecycleCallbacks(instance);
    }

    @Override public void onActivityResumed(final Activity activity) {
        if (activity == null) return;
        final View decor = activity.getWindow().getDecorView();
        apply(decor);
        try {
            ViewTreeObserver observer = decor.getViewTreeObserver();
            ViewTreeObserver.OnGlobalLayoutListener listener =
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
                            apply(decor);
                        }
                    };
            listeners.put(decor, listener);
            observer.addOnGlobalLayoutListener(listener);
        } catch (Throwable error) {
            Diary.note("navigation listener: " + error);
        }
    }

    @Override public void onActivityPaused(Activity activity) {
        if (activity == null) return;
        View decor = activity.getWindow().getDecorView();
        ViewTreeObserver.OnGlobalLayoutListener listener = listeners.remove(decor);
        if (listener != null) {
            try {
                if (decor.getViewTreeObserver().isAlive()) {
                    decor.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override public void onActivityCreated(Activity activity, android.os.Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}

    private void apply(final View root) {
        if (root == null || !root.isAttachedToWindow()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPass < QUIET_MS) return;
        lastPass = now;
        try {
            List<ImageView> icons = new ArrayList<ImageView>();
            collect(root, root, icons, 0);
            boolean found = false;
            for (ImageView icon : icons) {
                int kind = kind(icon, root);
                if (kind == 0) continue;
                if (styled.containsKey(icon) && styled.get(icon).intValue() == kind) {
                    updateSelected(icon);
                    found = true;
                    continue;
                }
                icon.setImageDrawable(new MaterialNavDrawable(kind, selected(icon)));
                icon.setContentDescription(icon.getContentDescription());
                icon.setOnTouchListener(new PressAnimation());
                styled.put(icon, Integer.valueOf(kind));
                found = true;
            }
            if (found) Diary.note("navigation: Material 3 icons applied");
        } catch (Throwable error) {
            Diary.note("navigation: " + error);
        }
    }

    private void updateSelected(ImageView icon) {
        Drawable drawable = icon.getDrawable();
        if (drawable instanceof MaterialNavDrawable) {
            ((MaterialNavDrawable) drawable).setSelected(selected(icon));
        }
    }

    private static boolean selected(View view) {
        if (view.isSelected()) return true;
        View parent = view;
        for (int i = 0; i < 3 && parent.getParent() instanceof View; i++) {
            parent = (View) parent.getParent();
            if (parent.isSelected()) return true;
        }
        return false;
    }

    private static void collect(View root, View view, List<ImageView> out, int depth) {
        if (view == null || depth > 24 || out.size() > 80) return;
        if (view instanceof ImageView) {
            if (nearBottom(root, view)) out.add((ImageView) view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collect(root, group.getChildAt(i), out, depth + 1);
        }
    }

    private static boolean nearBottom(View root, View view) {
        Rect rootBox = new Rect();
        Rect box = new Rect();
        if (!root.getGlobalVisibleRect(rootBox) || !view.getGlobalVisibleRect(box)) return false;
        int height = rootBox.height();
        int center = box.centerY() - rootBox.top;
        int size = Math.max(box.width(), box.height());
        return height > 0 && center > height * 0.70f && size >= 12 && size <= 120;
    }

    private static int kind(ImageView icon, View root) {
        StringBuilder labels = new StringBuilder();
        CharSequence description = icon.getContentDescription();
        if (description != null) labels.append(description).append(' ');
        try {
            int id = icon.getId();
            if (id != View.NO_ID) {
                labels.append(icon.getResources().getResourceEntryName(id)).append(' ');
            }
        } catch (Throwable ignored) {
        }
        View parent = icon;
        for (int i = 0; i < 3 && parent != null; i++) {
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            if (parent != null) labels.append(parent.getContentDescription()).append(' ');
        }
        String text = labels.toString().toLowerCase(Locale.US);
        if (contains(text, "profile", "personal", "account", "me", "mine", "user",
                "профил")) return PROFILE;
        if (contains(text, "friend", "friends", "following", "inbox", "друз")) return FRIENDS;
        if (contains(text, "home", "main", "feed", "for_you", "foryou", "глав")) return HOME;
        return 0;
    }

    private static boolean contains(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private static final class PressAnimation implements View.OnTouchListener {
        @Override public boolean onTouch(final View view, MotionEvent event) {
            try {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    view.animate().cancel();
                    view.animate().scaleX(0.86f).scaleY(0.86f).alpha(0.72f)
                            .setDuration(80L).start();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(180L).setStartDelay(15L).start();
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    private static final class MaterialNavDrawable extends Drawable {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean selected;

        MaterialNavDrawable(int kind, boolean selected) {
            this.kind = kind;
            this.selected = selected;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setBounds(0, 0, 24, 24);
        }

        void setSelected(boolean selected) {
            if (this.selected == selected) return;
            this.selected = selected;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            float w = getBounds().width();
            float h = getBounds().height();
            float sx = w / 24f;
            float sy = h / 24f;
            canvas.save();
            canvas.scale(sx, sy);
            int accent = 0xFFFE2C55;
            paint.setColor(selected ? accent : 0xFF6F6A73);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(selected ? 2.35f : 1.9f);
            if (kind == HOME) drawHome(canvas);
            else if (kind == FRIENDS) drawFriends(canvas);
            else drawProfile(canvas);
            canvas.restore();
        }

        private void drawHome(Canvas canvas) {
            path.reset();
            path.moveTo(4.1f, 10.7f);
            path.lineTo(12f, 4.1f);
            path.lineTo(19.9f, 10.7f);
            canvas.drawPath(path, paint);
            RectF body = new RectF(6.2f, 10f, 17.8f, 20f);
            canvas.drawRoundRect(body, 2.2f, 2.2f, paint);
            canvas.drawLine(10f, 20f, 10f, 14.5f, paint);
            canvas.drawLine(14f, 20f, 14f, 14.5f, paint);
        }

        private void drawFriends(Canvas canvas) {
            canvas.drawCircle(8.5f, 8.1f, 3.0f, paint);
            canvas.drawCircle(15.7f, 8.1f, 3.0f, paint);
            canvas.drawArc(new RectF(3.5f, 12f, 13.5f, 21f), 205f, 130f, false, paint);
            canvas.drawArc(new RectF(10.5f, 12f, 20.5f, 21f), 205f, 130f, false, paint);
        }

        private void drawProfile(Canvas canvas) {
            canvas.drawCircle(12f, 8f, 3.2f, paint);
            canvas.drawArc(new RectF(4.2f, 11.8f, 19.8f, 23f), 200f, 140f, false, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
    }
}
