package app.lightscout.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/**
 * Exported S-12 provider. It accepts only the platform update action and ignores all incoming extras.
 */
public final class GlucoseWidgetProvider extends AppWidgetProvider {

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId,
                                          Bundle newOptions) {
        onUpdate(context, manager, new int[]{appWidgetId});
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        if (context.getApplicationContext() instanceof WidgetDependenciesProvider provider) {
            GlucoseWidgetUpdater updater = provider.glucoseWidgetUpdater();
            if (updater != null) {
                updater.updateWidgets(appWidgetIds);
            }
        }
    }
}
