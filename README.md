# LightScout

LightScout is an Android app for parents and caregivers of a child with type 1 diabetes. It follows glucose
readings from the family's own [Nightscout](https://nightscout.github.io/) site and raises alarms that are meant to
ring: loud, repeating until you snooze them, and also through Do not disturb when you allow it.

> **LightScout is not a medical device.** It does not replace your CGM app, your pump, their alarms or a blood
> glucose meter. Read the [medical disclaimer](DISCLAIMER.md) before you rely on it.

<!-- Screenshots: to be added from device tests with synthetic data only (never real readings). -->

## What it does

- **Home screen** with the current reading, trend arrow, change since the previous reading, its age, and a bar that
  shows when the next check is due.
- **Chart** of the last hours, scrollable back in time, with Low / High guides and markers for carbs, boluses,
  BG checks, site and sensor changes and pump alerts.
- **Alarms** for Urgent low, Low, High, No data (no new reading for a set time) and Not running (the app itself has
  stopped checking). Alarms keep playing until you snooze them; the snooze length is set per alarm type.
- **Site and sensor age** counters with reminders before the end of their lifetime.
- **Entries** written to Nightscout: BG check, carbs, bolus, site change and sensor change. Entries made by
  LightScout can be edited or deleted.
- **Statistics** for up to 30 days: time in range, average, variability and the distribution of readings.
- **Home screen widget**, day and night themes.

Defaults (all can be changed): Urgent low 55 mg/dL, Low 70 mg/dL, High 250 mg/dL, No data after 20 minutes, target
range 70–180 mg/dL, site 3 days, sensor 10 days. Values are shown in mg/dL; mmol/L is planned.

## Requirements

| | |
|---|---|
| Phone | Android 10 (API 29) or newer. Tested on Android 16 (Xiaomi 13T, HyperOS). |
| Nightscout | **15.0.9 or newer**, reachable over **HTTPS** with a certificate trusted by Android. Older versions are refused by the app, because 15.0.9 contains API fixes LightScout depends on. |
| Access token | A Nightscout access token with read access and permission to create, update and delete treatments. Never your `API_SECRET`. See [how to create the token](docs/nightscout-token.md). |
| Data | Readings already reach Nightscout from your CGM uploader (for example xDrip+, nightscout-connect, Dexcom Share). LightScout only reads them. |

See [tested setups](docs/tested-setups.md) for configurations that are known to work.

## Install

LightScout is not in Google Play. Download the APK from [Releases](https://github.com/kukuchta/LightScout/releases):

1. Download the latest `LightScout-<version>.apk` on the phone.
2. Optional but recommended: compare the file's SHA-256 checksum with the one in the release notes.
3. Open the file and allow installing from this source when Android asks.
4. Open LightScout, accept the disclaimer, connect your Nightscout and go through the alarm readiness checklist.
5. Keep your existing alarms running in parallel for the first nights, until you trust the setup.

Updates are installed the same way, over the existing app. Releases are signed with the same key; Android refuses
an update signed with a different one.

**Nightly builds** (pre-releases named *Nightly*) are a separate app, *LightScout Nightly*, built from the latest code
for testing. Do not install them next to the stable app on a phone used for real alarms: both would ring.

## Documentation

- [Medical disclaimer](DISCLAIMER.md)
- [Privacy](PRIVACY.md)
- [Creating a Nightscout token, and what to do if it leaks](docs/nightscout-token.md)
- [FAQ](docs/faq.md)
- [Known limitations](docs/limitations.md)
- [Tested setups](docs/tested-setups.md)
- [Support and where to report problems](SUPPORT.md)
- [Security](SECURITY.md)
- [Development](docs/development.md) and [contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Support the project

LightScout is free and made by one person in their spare time. If it makes your days easier, you can
[buy me a coffee](https://buycoffee.to/kukuchta). It is completely optional and changes nothing in the app.

## License

Copyright © 2026 Jakub Kuchta.

LightScout is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. It is distributed in the hope that it will be useful, but **without any warranty**; without even the
implied warranty of merchantability or fitness for a particular purpose. See [LICENSE](LICENSE).

Nightscout is a separate project of the Nightscout community; LightScout is not affiliated with it, with Dexcom,
Medtronic or any other manufacturer.
