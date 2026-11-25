package app.lightscout.domain.alarm;

import java.util.Objects;

/**
 * Command for IF-16 {@code AlarmController.apply} (03 §6.2, CP-00A R-4).
 */
public sealed interface AlarmCommand permits AlarmCommand.Start, AlarmCommand.Replace,
        AlarmCommand.Update, AlarmCommand.Stop, AlarmCommand.ClearSnooze {

    /**
     * End the snooze of a type because its condition is gone (AL-5a, D-127 / D-128). A state change only: the
     * runtime has nothing to play or stop.
     *
     * @param type the type whose {@code snoozedUntil} is cleared
     */
    record ClearSnooze(AlarmType type) implements AlarmCommand {

        /**
         * Creates the command.
         *
         * @throws NullPointerException if {@code type} is null
         */
        public ClearSnooze {
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * Start ringing a type (idempotent in the runtime — R-35).
     *
     * @param type the type
     * @param view its view
     */
    record Start(AlarmType type, AlarmView view) implements AlarmCommand {

        /**
         * Creates the command.
         *
         * @throws NullPointerException if an argument is null
         */
        public Start {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(view, "view");
        }
    }

    /**
     * A higher-priority type replaces the ringing one (AL-6).
     *
     * @param fromType the replaced type
     * @param toType   the new type
     * @param view     the new type's view
     */
    record Replace(AlarmType fromType, AlarmType toType, AlarmView view) implements AlarmCommand {

        /**
         * Creates the command.
         *
         * @throws NullPointerException if an argument is null
         */
        public Replace {
            Objects.requireNonNull(fromType, "fromType");
            Objects.requireNonNull(toType, "toType");
            Objects.requireNonNull(view, "view");
        }
    }

    /**
     * Refresh the view of the ringing alarm (keep-alive, R-35).
     *
     * @param view the view
     */
    record Update(AlarmView view) implements AlarmCommand {

        /**
         * Creates the command.
         *
         * @throws NullPointerException if {@code view} is null
         */
        public Update {
            Objects.requireNonNull(view, "view");
        }
    }

    /**
     * Stop ringing a type. Emitted by the evaluator only for the switch-off rule (R-60, D-035).
     *
     * @param type the type
     */
    record Stop(AlarmType type) implements AlarmCommand {

        /**
         * Creates the command.
         *
         * @throws NullPointerException if {@code type} is null
         */
        public Stop {
            Objects.requireNonNull(type, "type");
        }
    }
}
