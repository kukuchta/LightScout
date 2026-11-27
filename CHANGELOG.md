# Changelog

All notable changes to LightScout are listed here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

- Alarm sounds: choose a sound for each alarm type (Urgent low, Low, No data, Not running, High) in Settings under
  Alarm sounds. Pick LightScout classic, a sound from the phone's alarm list, or add up to 5 of your own files. A
  play button tries a sound once. Removing an own sound can be undone for a few seconds.
- Settings: the four snooze times (Urgent low, Low, No data, High) moved from the alarm settings list to their own
  screen, Snooze times. Thresholds and alarm switches stay where they were.

## [0.1.0] – 2026

First public version.

- Home screen with the current reading, trend, change, age and the time to the next check.
- Chart with Low / High guides, scrolling back in time, and markers for carbs, boluses, BG checks, site and sensor
  changes and pump alerts.
- Alarms: Urgent low, Low, High, No data, Not running; they play until snoozed, with a snooze length per type.
- Site and sensor age counters with reminders; the value turns amber or red when a change is due or overdue.
- Entries written to Nightscout: BG check, carbs, bolus, site change, sensor change; own entries can be edited and
  deleted.
- Statistics for up to 30 days.
- Home screen widget, day and night themes, app icon.
- Faster first history load after installing or after a long pause.
- Optional support dialog.
- Once a day LightScout asks GitHub whether a newer stable version exists and, in a calm moment, offers it once per
  version. It sends no data about you. Can be turned off in Settings.

Known limitations: see [docs/limitations.md](docs/limitations.md).
