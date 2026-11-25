package app.lightscout.domain.settings;

/**
 * Appearance of the app screens (MainActivity): follow the system, always light or always dark. Alarm screen,
 * notifications and the widget are not affected (the alarm screen keeps its own dark window rule).
 */
public enum ThemeMode {
    /** Follow the system day / night setting (default). */
    SYSTEM(0),
    /** Always the light theme. */
    LIGHT(1),
    /** Always the dark theme. */
    DARK(2);

    private final int code;

    ThemeMode(int code) {
        this.code = code;
    }

    /**
     * Returns the stored code (0 system, 1 light, 2 dark).
     *
     * @return the code
     */
    public int code() {
        return code;
    }

    /**
     * Returns the mode of a stored code; an unknown code reads as {@link #SYSTEM}.
     *
     * @param code stored code
     * @return the mode
     */
    public static ThemeMode fromCode(int code) {
        for (ThemeMode m : values()) {
            if (m.code == code) {
                return m;
            }
        }
        return SYSTEM;
    }
}
