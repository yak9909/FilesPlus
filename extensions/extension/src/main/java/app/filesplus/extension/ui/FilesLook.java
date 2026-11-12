package app.filesplus.extension.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import app.filesplus.extension.DiagLog;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up the host app's own resources by name (never by id) and inflates its layouts outside the app's fragments.
 * The app's custom view classes ({@code com.google.android.apps.nbu.files.*}) must never be instantiated from here:
 * they fetch a Hilt peer in {@code onFinishInflate} and throw outside the app's own fragments. {@link #inflate}
 * therefore replaces them with plain framework or library views while inflating (DESIGN 5.11.2). Every method
 * returns null / the fallback instead of throwing.
 */
public final class FilesLook {
    private static final String APP_VIEW_PREFIX = "com.google.android.apps.nbu.files.";
    private static final String MATERIAL_BUTTON = "com.google.android.material.button.MaterialButton";

    /** Resolved ids by "type/name". A missing resource is cached as 0 too. Safe to call from any thread. */
    private static final ConcurrentHashMap<String, Integer> IDS = new ConcurrentHashMap<>();
    /** Problems are logged once per key: the builders run for every row of every screen. */
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<String>());

    private FilesLook() {}

    // ---- names -> ids -----------------------------------------------------------------------------------------

    /** The id of a resource of the given type ("layout", "style", "attr", "id", ...), or 0 when it does not exist. */
    public static int resource(Context context, String name, String type) {
        String key = type + '/' + name;
        Integer cached = IDS.get(key);
        if (cached != null) return cached;
        int id = 0;
        try {
            id = context.getResources().getIdentifier(name, type, context.getPackageName());
        } catch (Throwable t) {
            // Leave 0: callers fall back to their own look.
        }
        IDS.put(key, id);
        return id;
    }

    /** A view id ({@code @id/name}), or 0. */
    public static int id(Context context, String name) {
        return resource(context, name, "id");
    }

    /** A style resource id, or 0. */
    public static int style(Context context, String name) {
        return resource(context, name, "style");
    }

    /** The app string of that name, or null when missing. */
    public static String string(Context context, String name) {
        int id = resource(context, name, "string");
        if (id == 0) return null;
        try {
            return context.getString(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The app string of that name, or {@code fallback} when missing. */
    public static String string(Context context, String name, String fallback) {
        String value = string(context, name);
        return value != null ? value : fallback;
    }

    /** The app plural of that name for {@code count}, formatted with the count as its only argument; or null. */
    public static String plural(Context context, String name, int count) {
        int id = resource(context, name, "plurals");
        if (id == 0) return null;
        try {
            return context.getResources().getQuantityString(id, count, count);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The app drawable of that name, or null. The drawable is shared; mutate() it before tinting. */
    public static Drawable drawable(Context context, String name) {
        int id = resource(context, name, "drawable");
        if (id == 0) return null;
        try {
            return context.getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The app dimen of that name in pixels, or {@code fallbackDp} converted to pixels. */
    public static int dimenPx(Context context, String name, int fallbackDp) {
        int id = resource(context, name, "dimen");
        if (id != 0) {
            try {
                return context.getResources().getDimensionPixelSize(id);
            } catch (Throwable t) {
                // Fall through to the fallback.
            }
        }
        return dp(context, fallbackDp);
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Resolves a theme attribute (looked up by name) to its resource id, or 0. */
    public static int attrResource(Context context, String attrName) {
        int attr = resource(context, attrName, "attr");
        if (attr == 0) return 0;
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return 0;
        return value.resourceId;
    }

    /** A theme color attribute (looked up by name) resolved against the context's theme, or {@code fallback}. */
    public static int themeColor(Context context, String attrName, int fallback) {
        int attr = resource(context, attrName, "attr");
        if (attr == 0) return fallback;
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return fallback;
        if (value.resourceId != 0) {
            try {
                return context.getColorStateList(value.resourceId).getDefaultColor();
            } catch (Throwable t) {
                return fallback;
            }
        }
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? value.data : fallback;
    }

    // ---- dialogs ----------------------------------------------------------------------------------------------

    /**
     * The app's own platform alert dialog overlay already gives M3 buttons; the window itself would keep the
     * framework background, so draw the Material 3 dialog surface (surface container high, 28 dp corners).
     */
    public static void styleDialog(Context context, Dialog dialog) {
        try {
            Window window = dialog.getWindow();
            if (window == null) return;
            int color = themeColor(context, "colorSurfaceContainerHigh", themeColor(context, "colorSurface", 0));
            if (color == 0) return;
            GradientDrawable background = new GradientDrawable();
            background.setColor(color);
            background.setCornerRadius(dp(context, 28));
            // Same window inset the app's own dialog background uses (appcompat_dialog_background_inset).
            int inset = dimenPx(context, "appcompat_dialog_background_inset", 16);
            window.setBackgroundDrawable(new InsetDrawable(background, inset));
        } catch (Throwable t) {
            DiagLog.e("ui", "Cannot style dialog", t);
        }
    }

    // ---- layouts ----------------------------------------------------------------------------------------------

    /**
     * Inflates an app layout by name with the app's custom view classes replaced by plain views, or returns null
     * (missing layout or any failure; logged once). {@code parent} only supplies layout params; the view is not
     * attached. Use an Activity context whose theme is the app's (Material components need it).
     */
    public static View inflate(Context context, String layoutName, ViewGroup parent) {
        int layout = resource(context, layoutName, "layout");
        if (layout == 0) {
            logOnce("missing:" + layoutName, "Layout not found: " + layoutName, null);
            return null;
        }
        try {
            LayoutInflater inflater = LayoutInflater.from(context).cloneInContext(context);
            inflater.setFactory2(new Substitutes(inflater));
            return inflater.inflate(layout, parent, false);
        } catch (Throwable t) {
            logOnce("inflate:" + layoutName, "Cannot inflate " + layoutName, t);
            return null;
        }
    }

    private static void logOnce(String key, String message, Throwable t) {
        if (!LOGGED.add(key)) return;
        if (t != null) {
            DiagLog.e("ui", message, t);
        } else {
            DiagLog.i("ui", message);
        }
    }

    /**
     * Maps the app's custom tags (and a few library ones) to views that are safe outside the app's fragments.
     * Tags that are not mapped return null and go through the normal inflation. A failure while building a mapped
     * view propagates, so the whole inflate fails and the caller uses its fallback (never the app's own class).
     */
    private static final class Substitutes implements LayoutInflater.Factory2 {
        private final LayoutInflater inflater;

        Substitutes(LayoutInflater inflater) {
            this.inflater = inflater;
        }

        @Override
        public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
            if ("Button".equals(name)) {
                // AppCompat turns <Button> into a MaterialButton under the app's theme; do the same.
                return create(MATERIAL_BUTTON, context, attrs);
            }
            if ("ImageView".equals(name)) {
                // app:srcCompat is only understood by AppCompat's ImageView; apply it by hand.
                ImageView image = new ImageView(context, attrs);
                applySrcCompat(image, attrs);
                return image;
            }
            if ("androidx.recyclerview.widget.RecyclerView".equals(name)
                    || "android.support.v7.widget.RecyclerView".equals(name)) {
                return new ListView(context, attrs);
            }
            if (!name.startsWith(APP_VIEW_PREFIX)) return null;
            switch (name.substring(name.lastIndexOf('.') + 1)) {
                case "RowItemView":
                    return new CardLikeLayout(context, attrs);
                case "SquareItemView":
                    return new RelativeLayout(context, attrs);
                case "BrowseCapsuleItemView":
                case "FolderPickerListItemView":
                    return new LinearLayout(context, attrs);
                case "RoundedCornerImageView":
                    return new RoundedImageView(context, attrs);
                case "SelectionIndicatorView": {
                    View indicator = new View(context, attrs);
                    indicator.setVisibility(View.GONE);
                    return indicator;
                }
                case "BreadcrumbView":
                    return new HorizontalScrollView(context, attrs);
                case "GridLayoutRecyclerView":
                    return new ListView(context, attrs);
                default:
                    logOnce("unknown:" + name, "App view class replaced by FrameLayout: " + name, null);
                    return new FrameLayout(context, attrs);
            }
        }

        @Override
        public View onCreateView(String name, Context context, AttributeSet attrs) {
            return onCreateView(null, name, context, attrs);
        }

        private View create(String className, Context context, AttributeSet attrs) {
            try {
                return inflater.createView(context, className, null, attrs);
            } catch (ClassNotFoundException e) {
                throw new InflateException("Class not found: " + className, e);
            }
        }

        private static void applySrcCompat(ImageView image, AttributeSet attrs) {
            for (int i = 0; i < attrs.getAttributeCount(); i++) {
                if ("srcCompat".equals(attrs.getAttributeName(i))) {
                    int resource = attrs.getAttributeResourceValue(i, 0);
                    if (resource != 0) image.setImageResource(resource);
                    return;
                }
            }
        }
    }

    /**
     * Stand-in for the app's RowItemView. The original extends androidx CardView with the default style, not
     * MaterialCardView (which would add the app theme's outlined style: stroke and M3 corners). A FrameLayout keeps
     * the layout's attributes (foreground, margins) and draws the plain card look: the theme's window background,
     * CardView's default 2 dp radius, no elevation (the layout sets cardElevation to 0).
     */
    private static final class CardLikeLayout extends FrameLayout {
        CardLikeLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
            TypedValue background = new TypedValue();
            int color = context.getTheme().resolveAttribute(android.R.attr.colorBackground, background, true)
                    && background.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && background.type <= TypedValue.TYPE_LAST_COLOR_INT ? background.data : 0;
            if (color == 0) color = themeColor(context, "colorSurface", 0xffffffff);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color);
            shape.setCornerRadius(dimenPx(context, "cardview_default_radius", 2));
            setBackground(shape);
            setClipToOutline(true);
        }
    }

    /**
     * Stand-in for the app's RoundedCornerImageView: an ImageView clipped to the layout's {@code corner_radius}
     * (read from the XML reference or literal, so it is the original value), square when the layout asks for a
     * shape. Not reproduced: the other shape values (none are used by the layouts we inflate) and
     * {@code background_color}.
     */
    private static final class RoundedImageView extends ImageView {
        private final boolean square;

        RoundedImageView(Context context, AttributeSet attrs) {
            super(context, attrs);
            boolean hasShape = false;
            float radius = -1f;
            for (int i = 0; i < attrs.getAttributeCount(); i++) {
                String name = attrs.getAttributeName(i);
                if ("shape".equals(name)) {
                    hasShape = true;
                } else if ("corner_radius".equals(name)) {
                    // Resolves both a dimen reference and a literal such as "4.0dp"; anything that cannot be
                    // decoded stays -1 ("unknown") and uses the named dimen below.
                    int attr = resource(context, "corner_radius", "attr");
                    if (attr != 0) {
                        TypedArray array = context.obtainStyledAttributes(attrs, new int[] {attr});
                        try {
                            if (array.hasValue(0)) radius = array.getDimension(0, -1f);
                        } catch (Throwable t) {
                            // Use the named dimen below.
                        } finally {
                            array.recycle();
                        }
                    }
                }
            }
            square = hasShape;
            if (radius < 0) {
                radius = dimenPx(context, square ? "square_view_item_corner_radius" : "thumbnail_corner_size", 4);
            }
            final float corner = radius;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            });
            setClipToOutline(true);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (square) setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
        }
    }
}
