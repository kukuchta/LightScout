/**
 * MOD-07 {@code :alarm} — alarm runtime and S-09 (CP-07): {@link app.lightscout.alarm.AlarmRuntime} implements
 * IF-16, {@code AlarmPlayer} plays the escalating alarm on the alarm stream until „Snooze”, the CH-01 alarm
 * notification carries the full-screen intent and the Snooze action, {@link app.lightscout.alarm.AlarmActivity} is
 * S-09 over the lock screen and {@link app.lightscout.alarm.SnoozeReceiver} handles the notification action.
 *
 * <p>Depends on {@code :domain}, {@code :usecases}, {@code :platform} and {@code :ui-core} only (CP-00 §3, R-3); it
 * never references {@code :monitor}, {@code :widget}, {@code :storage} or {@code :nightscout}. Collaborators are
 * injected by the CP-15 composition root through the {@link app.lightscout.alarm.AlarmRuntime} constructor and
 * {@link app.lightscout.alarm.AlarmRuntimeProvider}.
 */
package app.lightscout.alarm;
