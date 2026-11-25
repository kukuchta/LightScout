package app.lightscout.feature.onboarding;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/**
 * Accessibility helpers for the onboarding screens (CP-14 §7.2 / §7.3 Accessibility, TST-06 TalkBack).
 */
public final class A11y {

    private A11y() {
    }

    /**
     * Marks the title view of a toolbar as a heading (S-05 „title is a heading”).
     *
     * @param toolbar the screen toolbar with its title already set
     */
    public static void markTitleAsHeading(Toolbar toolbar) {
        CharSequence title = toolbar.getTitle();
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof TextView && title != null
                    && title.toString().contentEquals(((TextView) child).getText())) {
                ViewCompat.setAccessibilityHeading(child, true);
            }
        }
    }

    /**
     * Exposes a container as a list of its visible children (S-05 „list read as list”; S-06 result rows).
     *
     * @param list the container whose direct children are the items
     */
    public static void markAsList(ViewGroup list) {
        ViewCompat.setAccessibilityDelegate(list, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                    @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(
                        visibleChildren(list), 1, false,
                        AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_NONE));
            }
        });
        for (int i = 0; i < list.getChildCount(); i++) {
            markAsListItem(list, list.getChildAt(i));
        }
    }

    private static void markAsListItem(ViewGroup list, View item) {
        ViewCompat.setAccessibilityDelegate(item, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                    @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                        visibleIndex(list, host), 1, 0, 1, false, false));
            }
        });
    }

    private static int visibleChildren(ViewGroup list) {
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).getVisibility() == View.VISIBLE) {
                n++;
            }
        }
        return n;
    }

    private static int visibleIndex(ViewGroup list, View item) {
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c == item) {
                return n;
            }
            if (c.getVisibility() == View.VISIBLE) {
                n++;
            }
        }
        return n;
    }
}
