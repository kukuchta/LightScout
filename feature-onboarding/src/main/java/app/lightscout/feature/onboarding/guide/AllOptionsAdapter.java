package app.lightscout.feature.onboarding.guide;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * C-S08-03 dropdown adapter that never filters (IR-97): the exposed dropdown always lists all six options, also
 * after the field text was restored on recreate / process death (A-S08-1, GIR-2).
 */
final class AllOptionsAdapter extends ArrayAdapter<String> {

    private final Filter noFilter;

    /**
     * Creates the adapter.
     *
     * @param context  a themed context
     * @param resource the item layout
     * @param options  the option labels in C-S08-03 order
     */
    AllOptionsAdapter(@NonNull Context context, int resource, @NonNull List<String> options) {
        super(context, resource, options);
        final List<String> all = List.copyOf(options);
        noFilter = new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                results.values = all;
                results.count = all.size();
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                notifyDataSetChanged();
            }
        };
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return noFilter;
    }
}
