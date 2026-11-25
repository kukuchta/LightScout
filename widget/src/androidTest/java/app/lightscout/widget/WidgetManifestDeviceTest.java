package app.lightscout.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.lightscout.ui.core.AppLaunch;

@RunWith(AndroidJUnit4.class)
public class WidgetManifestDeviceTest {

    @Test
    public void providerIsExportedAndHasOnlyTheWidgetUpdateAction() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ComponentName component = new ComponentName(context, GlucoseWidgetProvider.class);
        ActivityInfo receiver = context.getPackageManager().getReceiverInfo(component,
                PackageManager.GET_META_DATA);
        assertTrue(receiver.exported);
        assertEquals("android.permission.BIND_APPWIDGET", receiver.permission);
        assertNotNull(receiver.metaData);

        // Query by action and package, not by component: an explicit component is returned without matching the filters.
        Intent update = new Intent("android.appwidget.action.APPWIDGET_UPDATE").setPackage(context.getPackageName());
        assertTrue(!context.getPackageManager().queryBroadcastReceivers(update, PackageManager.MATCH_ALL).isEmpty());
        Intent extraAction = new Intent("app.lightscout.widget.UNEXPECTED").setPackage(context.getPackageName());
        assertTrue(context.getPackageManager().queryBroadcastReceivers(extraAction, PackageManager.MATCH_ALL).isEmpty());
    }

    @Test
    public void providerMetadataDisablesPeriodicUpdatesAndLayoutInflates() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ActivityInfo receiver = context.getPackageManager().getReceiverInfo(
                new ComponentName(context, GlucoseWidgetProvider.class), PackageManager.GET_META_DATA);
        int providerXml = receiver.metaData.getInt("android.appwidget.provider");
        XmlResourceParser parser = context.getResources().getXml(providerXml);
        int minWidth = -1;
        int minHeight = -1;
        int updatePeriodMillis = -1;
        int resizeMode = -1;
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlResourceParser.START_TAG
                    && "appwidget-provider".equals(parser.getName())) {
                android.util.AttributeSet attrs = parser;
                android.content.res.TypedArray values = context.obtainStyledAttributes(attrs,
                        new int[]{android.R.attr.minWidth, android.R.attr.minHeight,
                                android.R.attr.updatePeriodMillis, android.R.attr.resizeMode});
                minWidth = values.getDimensionPixelSize(0, 0);
                minHeight = values.getDimensionPixelSize(1, 0);
                updatePeriodMillis = values.getInt(2, -1);
                resizeMode = values.getInt(3, 0);
                values.recycle();
                break;
            }
        }
        parser.close();
        assertTrue(minWidth > 0);
        assertTrue(minHeight > 0);
        assertEquals(0, updatePeriodMillis);
        assertEquals(3, resizeMode);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_glucose);
        assertNotNull(views.apply(context, new FrameLayout(context)));
    }

    @Test
    public void tapIntentIsExplicitAndUsesLauncherClearTop() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = AppLaunch.mainActivityIntent(context);
        assertNotNull(intent.getComponent());
        assertEquals(context.getPackageName(), intent.getComponent().getPackageName());
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    }
}
