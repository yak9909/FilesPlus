package app.filesplus.extension.settings;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;

/** Implemented in the app fragment by the patch; all renamed members are resolved from app instructions. */
public interface PrefBridge {
    Activity host();
    Object newScreen(Object original);
    /** The Context the app's own builder gives its preferences (carries the preference theme). */
    Context context(Object preference);
    Object newCategory(Context context);
    Object newPreference(Context context);
    Object newSwitch(Context context);
    void prepare(Object preference);
    void categoryStyle(Object preference);
    void setKey(Object preference, String key);
    void setTitle(Object preference, CharSequence title);
    void setSummary(Object preference, CharSequence summary);
    void setIcon(Object preference, int resource);
    void setChecked(Object preference, boolean checked);
    Class<?> clickType();
    Class<?> changeType();
    void setClick(Object preference, Object listener);
    void setChange(Object preference, Object listener);
    void addTo(Object group, Object preference);
    void publish(Object replacement);
    Dialog dialog(Context context, CharSequence title, View content, CharSequence positive, CharSequence negative);
    Dialog choices(Context context, CharSequence title, CharSequence[] items, int selected,
            android.content.DialogInterface.OnClickListener listener);
}
