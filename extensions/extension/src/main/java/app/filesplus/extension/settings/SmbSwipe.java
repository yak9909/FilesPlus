package app.filesplus.extension.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Left swipe for the SMB connection preferences, installed as native holders are bound. */
public final class SmbSwipe implements View.OnTouchListener, View.OnAttachStateChangeListener {
    private static final Map<Object, Consumer<Runnable>> ACTIONS = new WeakHashMap<>();
    private static final Map<View, WeakReference<SmbSwipe>> ROWS = new WeakHashMap<>();

    private final Consumer<Runnable> confirm;
    private final Reveal reveal;
    private final int slop;
    private ValueAnimator animation;
    private float downX;
    private float downY;
    private float distance;
    private boolean tracking;
    private boolean dragging;
    private boolean confirming;

    static void register(Object preference, Consumer<Runnable> confirm) {
        ACTIONS.put(preference, confirm);
    }

    /** Called after every native Preference bind so recycled holders lose any old action. */
    public static void bind(Object preference, View row) {
        try {
            WeakReference<SmbSwipe> previous = ROWS.remove(row);
            SmbSwipe old = previous == null ? null : previous.get();
            if (old != null) {
                old.reset(row);
                row.removeOnAttachStateChangeListener(old);
                row.setOnTouchListener(null);
            }
            Consumer<Runnable> confirm = ACTIONS.get(preference);
            if (confirm == null || !(row instanceof ViewGroup)) return;
            SmbSwipe swipe = new SmbSwipe(row, confirm);
            ROWS.put(row, new WeakReference<>(swipe));
            row.setOnTouchListener(swipe);
            row.addOnAttachStateChangeListener(swipe);
        } catch (Throwable t) { SettingsPages.hookError(t); }
    }

    private SmbSwipe(View row, Consumer<Runnable> confirm) {
        this.confirm = confirm;
        Context theme = row.getContext();
        slop = ViewConfiguration.get(theme).getScaledTouchSlop();
        reveal = new Reveal(theme);
    }

    @Override public boolean onTouch(View row, MotionEvent event) {
        try {
            return touch(row, event);
        } catch (Throwable t) {
            SettingsPages.hookError(t);
            try { reset(row); }
            catch (Throwable resetError) { SettingsPages.hookError(resetError); }
            return false;
        }
    }

    private boolean touch(View row, MotionEvent event) {
        if (confirming) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                reset(row);
                tracking = true;
                downX = event.getX();
                downY = event.getY();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                float left = downX - event.getX();
                if (!dragging) {
                    float vertical = Math.abs(event.getY() - downY);
                    if (vertical > slop && vertical >= Math.abs(left) || left < -slop) {
                        tracking = false;
                        return false;
                    }
                    if (left <= slop || left <= vertical) return false;
                    dragging = true;
                    row.getParent().requestDisallowInterceptTouchEvent(true);
                    MotionEvent cancel = MotionEvent.obtain(event);
                    try {
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        row.onTouchEvent(cancel);
                    } finally {
                        cancel.recycle();
                    }
                    row.getOverlay().add(reveal);
                }
                draw(row, Math.max(0, Math.min(left - slop, row.getWidth() / 2f)));
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                tracking = dragging = false;
                if (distance >= Math.min(row.getWidth() / 3f, 3f * reveal.iconSize)) {
                    confirming = true;
                    confirm.accept(() -> {
                        try {
                            confirming = false;
                            WeakReference<SmbSwipe> current = ROWS.get(row);
                            if (current != null && current.get() == this && row.isAttachedToWindow()) spring(row);
                        } catch (Throwable t) { SettingsPages.hookError(t); }
                    });
                } else {
                    spring(row);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                boolean consumed = dragging;
                if (dragging) spring(row);
                tracking = dragging = false;
                return consumed;
            default: return dragging;
        }
    }

    private void spring(View row) {
        if (animation != null) animation.cancel();
        animation = ValueAnimator.ofFloat(distance, 0);
        animation.setDuration(180);
        animation.addUpdateListener(value -> {
            try {
                draw(row, (Float) value.getAnimatedValue());
                if (distance == 0) row.getOverlay().remove(reveal);
            } catch (Throwable t) { SettingsPages.hookError(t); }
        });
        animation.start();
    }

    private void draw(View row, float amount) {
        distance = amount;
        ViewGroup group = (ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setTranslationX(-amount);
        reveal.setBounds(row.getWidth() - Math.round(amount), 0, row.getWidth(), row.getHeight());
    }

    private void reset(View row) {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
        if (distance != 0) draw(row, 0);
        row.getOverlay().remove(reveal);
        tracking = dragging = confirming = false;
    }

    @Override public void onViewAttachedToWindow(View view) {}

    @Override public void onViewDetachedFromWindow(View view) {
        try { reset(view); }
        catch (Throwable t) { SettingsPages.hookError(t); }
    }

    private static final class Reveal extends Drawable {
        final Drawable icon;
        final int iconSize;
        final Paint paint = new Paint();

        Reveal(Context context) {
            int iconId = resource(context, "drawable", "quantum_gm_ic_delete_vd_theme_24");
            icon = context.getDrawable(iconId).mutate();
            iconSize = icon.getIntrinsicWidth();
            int error = resource(context, "attr", "colorError");
            int onError = resource(context, "attr", "colorOnError");
            TypedArray theme = context.obtainStyledAttributes(new int[]{error, onError});
            try {
                if (!theme.hasValue(0) || !theme.hasValue(1)) throw new IllegalStateException("Missing delete colors");
                paint.setColor(theme.getColor(0, 0));
                icon.setTint(theme.getColor(1, 0));
            } finally {
                theme.recycle();
            }
        }

        private static int resource(Context context, String type, String name) {
            int id = context.getResources().getIdentifier(name, type, context.getPackageName());
            if (id == 0) throw new IllegalStateException("Missing delete " + name);
            return id;
        }

        @Override public void draw(Canvas canvas) {
            canvas.drawRect(getBounds(), paint);
            if (getBounds().width() < iconSize) return;
            int left = getBounds().centerX() - iconSize / 2;
            int top = getBounds().centerY() - iconSize / 2;
            icon.setBounds(left, top, left + iconSize, top + iconSize);
            icon.draw(canvas);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
