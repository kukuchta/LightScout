package app.lightscout.domain.port;

import app.lightscout.domain.alarm.AlarmCommand;

import java.util.List;

/**
 * Alarm runtime port (IF-16, CP-00A R-35), implemented by {@code :alarm}. Called once per evaluation on
 * {@code monitorExecutor}, possibly with an empty list.
 */
public interface AlarmController {

    /**
     * Applies the commands of one evaluation.
     *
     * @param commands commands, possibly empty
     */
    void apply(List<AlarmCommand> commands);
}
