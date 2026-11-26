# Known limitations

These are known and accepted. They are why LightScout must not be your only alarm.

## Alarms and timing

- **LightScout checks Nightscout about once a minute.** A new reading appears in the app when it has reached
  Nightscout, which already includes the delay of your CGM and uploader (often 1–5 minutes, more with cloud
  followers such as Dexcom Share or CareLink).
- **Phone makers delay background work.** On Xiaomi / HyperOS, checks can be delayed by about 2 minutes while the
  screen is off. Other makers (Samsung, Huawei, OnePlus and others) have similar battery savers. Follow the alarm
  readiness checklist in the app and see [dontkillmyapp.com](https://dontkillmyapp.com/).
- **After a phone restart**, monitoring resumes automatically only if the phone allows the app to start at boot. On
  Xiaomi this needs **Autostart**; without it, monitoring resumes when you open the app.
- **Without internet, LightScout cannot know new readings.** After the set time it raises the No data alarm; it
  cannot ring for a low it has not seen.
- **If the app itself stops** (killed by the system, phone off), it cannot ring. The Not running alarm and the
  status notification help to notice this, but cannot cover a phone that is off or out of battery.
- **Do not disturb and volume.** Alarms use the alarm sound channel. If you do not allow LightScout to override
  Do not disturb, or the alarm volume is down, an alarm can be silent. The readiness checklist warns about this.
- **One alarm sound.** All alarm types use the same sound for now. A choice of sounds is planned.

## Data

- **LightScout trusts the data in Nightscout.** A Dexcom error code (sensor values 1–12) uploaded as a reading is
  shown as a glucose value and **can ring a false Urgent low**.
- **One Nightscout site, one person.** LightScout follows one site. It is designed for one child.
- **The local copy covers the last 31 days.** Older history is loaded from Nightscout when you scroll there and is
  not kept offline.
- **Only entries created by LightScout can be edited or deleted** in the app. Entries from other apps are shown
  read-only.
- **mg/dL only** for now. mmol/L is planned.

## Platform

- Android 10 or newer. Tested on a single phone model (Xiaomi 13T, HyperOS, Android 16); other phones are expected
  to work but are not tested by the author.
- Nightscout 15.0.9 or newer, HTTPS with a certificate trusted by Android (self-signed certificates do not work).
- English only for now. Polish is planned.
