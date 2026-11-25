package app.lightscout.storage;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Hand-written in-memory {@link SharedPreferences} for JVM tests (no Robolectric / Mockito, CP-00 §2).
 * Typed getters throw {@link ClassCastException} on a type mismatch like the platform does. {@link #failCommits}
 * makes {@code commit()} return {@code false}; {@link #failReads} makes every read throw.
 */
final class FakeSharedPreferences implements SharedPreferences {

    final Map<String, Object> values = new LinkedHashMap<>();
    boolean failCommits;
    boolean failReads;
    int commits;
    int applies;

    @Override
    public Map<String, ?> getAll() {
        checkRead();
        return new HashMap<>(values);
    }

    @Override
    public String getString(String key, String defValue) {
        checkRead();
        return values.containsKey(key) ? (String) values.get(key) : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        checkRead();
        return values.containsKey(key) ? (Set<String>) values.get(key) : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        checkRead();
        return values.containsKey(key) ? (Integer) values.get(key) : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        checkRead();
        return values.containsKey(key) ? (Long) values.get(key) : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        checkRead();
        return values.containsKey(key) ? (Float) values.get(key) : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        checkRead();
        return values.containsKey(key) ? (Boolean) values.get(key) : defValue;
    }

    @Override
    public boolean contains(String key) {
        checkRead();
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        throw new UnsupportedOperationException();
    }

    private void checkRead() {
        if (failReads) {
            throw new IllegalStateException("simulated read failure");
        }
    }

    private final class FakeEditor implements Editor {

        private final Map<String, Object> puts = new LinkedHashMap<>();
        private final Set<String> removes = new HashSet<>();
        private boolean clear;

        @Override
        public Editor putString(String key, String value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            puts.put(key, values == null ? null : new HashSet<>(values));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            removes.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            if (failCommits) {
                return false;
            }
            commits++;
            applyChanges();
            return true;
        }

        @Override
        public void apply() {
            applies++;
            applyChanges();
        }

        private void applyChanges() {
            if (clear) {
                values.clear();
            }
            for (String key : removes) {
                values.remove(key);
            }
            for (Map.Entry<String, Object> e : puts.entrySet()) {
                if (e.getValue() == null) {
                    values.remove(e.getKey());
                } else {
                    values.put(e.getKey(), e.getValue());
                }
            }
        }
    }
}
