package app.lightscout.feature.entry;

import androidx.annotation.NonNull;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.SavedStateHandleSupport;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;

import java.util.Objects;
import java.util.function.Function;

/**
 * Manual-DI ViewModel factory (CP-00 R-6: no Hilt / Dagger): builds one ViewModel type from its
 * {@link SavedStateHandle}. Works for fragment and navigation-graph owners because both supply the saved-state
 * creation extras.
 */
public final class ViewModelFactories {

    private ViewModelFactories() {
    }

    /**
     * Returns a factory for one ViewModel class.
     *
     * @param type    the ViewModel class
     * @param creator builds the ViewModel from its saved-state handle
     * @param <V>     the ViewModel type
     * @return the factory
     */
    public static <V extends ViewModel> ViewModelProvider.Factory of(Class<V> type,
            Function<SavedStateHandle, V> creator) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(creator, "creator");
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass, @NonNull CreationExtras extras) {
                if (!modelClass.isAssignableFrom(type)) {
                    throw new IllegalArgumentException("Unsupported ViewModel " + modelClass.getName());
                }
                return modelClass.cast(creator.apply(SavedStateHandleSupport.createSavedStateHandle(extras)));
            }
        };
    }
}
