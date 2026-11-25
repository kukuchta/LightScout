package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.util.TypedValue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.textfield.TextInputLayout;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * §9 "Theme": no dynamic colour (the resolved roles are the static LightScout values, independent of the
 * wallpaper), Outlined text fields, app bar without elevation shadow and lifting by colour.
 *
 * <p>Device: RD-2 (Android 13, API 33 — i.e. API 31+ where dynamic colour exists) over USB — batched run
 * (CP-00 §10 CR-04). Compiled in SS-08. The wallpaper-change part of the acceptance is observed manually in the
 * device session: change the wallpaper, re-run this test; it must stay green.
 */
@RunWith(AndroidJUnit4.class)
public class ThemeDeviceTest {

    private static int resolve(Context c, int attr) {
        TypedValue v = new TypedValue();
        c.getTheme().resolveAttribute(attr, v, true);
        return v.resourceId != 0 ? c.getColor(v.resourceId) : v.data;
    }

    @Test
    public void colourRolesAreTheStaticScheme() {
        Context light = DeviceContexts.themed(false);
        Context dark = DeviceContexts.themed(true);
        assertEquals(0xFF3F5F90, resolve(light, androidx.appcompat.R.attr.colorPrimary));
        assertEquals(0xFFF9F9FF, resolve(light, com.google.android.material.R.attr.colorSurface));
        assertEquals(0xFFA8C8FF, resolve(dark, androidx.appcompat.R.attr.colorPrimary));
        assertEquals(0xFF111318, resolve(dark, com.google.android.material.R.attr.colorSurface));
        assertEquals(0xFF7E5700, resolve(light, R.attr.warning));
        assertEquals(0xFFFFDEAC, resolve(light, R.attr.warningContainer));
        assertEquals(0xFFFFBA38, resolve(dark, R.attr.warning));
        assertEquals(0xFF604100, resolve(dark, R.attr.warningContainer));
    }

    @Test
    public void textFieldsAreOutlined() {
        Context c = DeviceContexts.themed(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TextInputLayout field = new TextInputLayout(c);
            assertEquals(TextInputLayout.BOX_BACKGROUND_OUTLINE, field.getBoxBackgroundMode());
        });
    }

    @Test
    public void appBarHasNoShadowAndLiftsOnScroll() {
        Context c = DeviceContexts.themed(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AppBarLayout appBar = new AppBarLayout(c);
            assertEquals(0f, appBar.getElevation(), 0f);
            assertNull(appBar.getStateListAnimator());
            assertEquals(true, appBar.isLiftOnScroll());
        });
    }
}
