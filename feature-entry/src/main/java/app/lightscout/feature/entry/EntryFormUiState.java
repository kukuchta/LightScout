package app.lightscout.feature.entry;

import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.entry.SourceLabel;
import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import java.time.Instant;

/**
 * Immutable render state of S-02 (CP-13 §7.3 – §7.6). Display mapping only; every rule is decided by
 * {@link EntryFormModel} with IF-06 / IF-23 (ADR-023).
 *
 * @param mode              CREATE or EDIT (title, offline copy variant)
 * @param type              selected type (C-S02-05); {@code null} = none (DS:D-053)
 * @param typeLocked        edit mode: only the selected chip stays enabled (CP-00A R-75)
 * @param fieldsEnabled     chips / value / time accept input (not loading, saving, deleting or after a conflict)
 * @param helperChooseType  helper „Choose what to log” under the chips
 * @param valueVisible      C-S02-06 shown (BG check, Carbs, Bolus)
 * @param valueText         the value text as typed
 * @param valueFieldVersion incremented when the model replaces the text (edit load); the field is rewritten then
 * @param valueError        range error under the value; {@code null} = none
 * @param time              chosen time; {@code null} = „Now” (resolved at Save, R-75)
 * @param timeError         time error after the picker / at Save; {@code null} = none
 * @param progress          C-S02-04 (loading, saving or deleting)
 * @param saveEnabled       C-S02-03 enabled (§7.5 Save-enabled rule)
 * @param deleteVisible     C-S02-12 shown (edit mode)
 * @param deleteEnabled     C-S02-12 enabled
 * @param message           C-S02-11 text; {@link EntryFormModel.Message#NONE} = hidden
 * @param similar           C-S02-10 content; {@code null} = hidden
 * @param discardDialog     C-S02-14 open
 * @param deleteDialog      C-S02-13 open
 * @param backGuard         unsaved changes: the back callback is enabled (06 §11 F17)
 */
public record EntryFormUiState(EntryMode mode, EntryType type, boolean typeLocked, boolean fieldsEnabled,
                               boolean helperChooseType, boolean valueVisible, String valueText,
                               int valueFieldVersion, ValidationCode valueError, Instant time,
                               ValidationCode timeError, boolean progress, boolean saveEnabled,
                               boolean deleteVisible, boolean deleteEnabled, EntryFormModel.Message message,
                               Similar similar, boolean discardDialog, boolean deleteDialog, boolean backGuard) {

    /** Direction of a similar entry relative to the chosen time (CP-00A R-83, DCR-04.5). */
    public enum Direction {
        /** Less than one minute apart: {@code s02_similar_entry_same_time}. */
        SAME_TIME,
        /** Before the chosen time: {@code s02_similar_entry_earlier}. */
        EARLIER,
        /** After the chosen time: {@code s02_similar_entry_later}. */
        LATER
    }

    /**
     * C-S02-10 content (DS:D-027).
     *
     * @param entry     the nearest similar entry (value formatted by {@code Formatters.entryValue}, R-82)
     * @param minutes   whole minutes between the entry and the chosen time (floor)
     * @param direction earlier / later / same time
     * @param source    M-6 source label ({@code EntryRules.sourceLabel})
     */
    public record Similar(Entry entry, long minutes, Direction direction, SourceLabel source) {
    }
}
