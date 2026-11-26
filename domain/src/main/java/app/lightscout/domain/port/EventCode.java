package app.lightscout.domain.port;

/**
 * Closed catalogue of log event codes (IF-66, CP-00A R-13). Extended only by amending CP-00A.
 * Line format: {@code <ISO-8601 UTC> <LEVEL> <CODE>[:<detail>]}.
 */
public enum EventCode {
    /** Sync tick succeeded. */
    SYNC_OK,
    /** Sync tick failed; detail {@code FetchOutcome:DetailCode}. */
    SYNC_FAIL,
    /** Tick ran late; detail ms. */
    TICK_LATE,
    /** Store failure; detail op. */
    STORAGE_ERROR,
    /** Documents skipped by the mapping; detail {@code collection:count}. */
    MAPPING_SKIPPED,
    /** JWT refresh failed. */
    JWT_REFRESH_FAIL,
    /** Redirect refused. */
    REDIRECT_REFUSED,
    /** Response too large. */
    RESPONSE_TOO_LARGE,
    /** Daily check; detail result. */
    DAILY_CHECK,
    /** Entry write; detail {@code WriteKind} or {@code DetailCode}. */
    ENTRY_WRITE,
    /** Alarm started ringing; detail type. */
    ALARM_RING,
    /** Alarm replaced; detail {@code from>to}. */
    ALARM_REPLACE,
    /** Alarm snoozed; detail type. */
    ALARM_SNOOZE,
    /** Alarm stopped; detail type. */
    ALARM_STOP,
    /** Alarm sound fallback; detail stage. */
    ALARM_SOUND_FALLBACK,
    /** DCR-51: an alarm started with a chosen sound; detail {@code SoundKind} only, never a name, URI or path. */
    ALARM_SOUND,
    /** DCR-51: a phone sound or a user file could not be copied; detail {@code ImportOutcome}. */
    SOUND_IMPORT_FAIL,
    /** Watchdog fired. */
    WATCHDOG_FIRE,
    /** Heartbeat. */
    HEARTBEAT,
    /** Foreground service started; detail origin. */
    FGS_START,
    /** Foreground service start denied; detail origin. */
    FGS_START_DENIED,
    /** Exact alarm permission denied. */
    EXACT_ALARM_DENIED,
    /** Boot while locked: monitoring paused. */
    BOOT_PAUSED,
    /** Monitoring resumed after boot. */
    BOOT_RESUMED,
    /** A boot was not handled. */
    BOOT_NOT_HANDLED,
    /** DCR-36: the first tick after a system start did not raise NOT_RUNNING, the phone was off. */
    WATCHDOG_SKIPPED_BOOT,
    /** Clock or time zone changed. */
    TIME_CHANGED,
    /** Network lost. */
    NET_LOST,
    /** Network regained. */
    NET_REGAINED,
    /** Alarm channel re-created. */
    CHANNEL_RECREATED,
    /** Readiness items missing; detail count. */
    READINESS_MISSING,
    /** Keystore key invalidated. */
    KEY_INVALIDATED,
    /** Credential-encrypted storage accessed while locked. */
    CE_ACCESS_LOCKED,
    /** Crash recorded. */
    CRASH;

    /**
     * Severity of a log line (CP-00A R-13).
     */
    public enum Level {
        /** Informational. */
        INFO,
        /** Warning. */
        WARN,
        /** Error. */
        ERROR
    }

    /**
     * Returns the level of this event: WARN for {@code *_FAIL}, {@code *_DENIED}, {@code REDIRECT_REFUSED},
     * {@code RESPONSE_TOO_LARGE}, {@code BOOT_NOT_HANDLED}; ERROR for {@code STORAGE_ERROR}, {@code CRASH};
     * otherwise INFO.
     *
     * @return the level
     */
    public Level level() {
        return switch (this) {
            case SYNC_FAIL, JWT_REFRESH_FAIL, FGS_START_DENIED, EXACT_ALARM_DENIED,
                 REDIRECT_REFUSED, RESPONSE_TOO_LARGE, BOOT_NOT_HANDLED, SOUND_IMPORT_FAIL -> Level.WARN;
            case STORAGE_ERROR, CRASH -> Level.ERROR;
            default -> Level.INFO;
        };
    }
}
