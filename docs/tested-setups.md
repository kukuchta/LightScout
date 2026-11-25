# Tested setups

Configurations known to work with LightScout. "Tested" means used every day, including nights, in parallel with the
existing alarms; it is not a guarantee for your setup.

Share yours in [Discussions → Setups](https://github.com/kukuchta/LightScout/discussions) (without addresses, tokens
or real readings).

| Nightscout | Hosting | Glucose data | Treatments (meals, boluses) | Phone | Since |
|---|---|---|---|---|---|
| 15.0.9 | [Mikr.us](https://mikr.us/) VPS | Dexcom via Dexcom Share, uploaded by the nightscout-connect plugin | Medtronic MiniMed 780G via xDrip+ in CareLink follower mode | Xiaomi 13T, HyperOS, Android 16 | 2026, author's setup, run for many nights in parallel with xDrip+ |

## Notes on these setups

- **Cloud followers add delay.** Dexcom Share and CareLink are polled by the uploader, so readings reach Nightscout
  some minutes after the sensor. LightScout cannot be faster than Nightscout.
- **HTTPS is required.** The Mikr.us setup uses a certificate trusted by Android.
- **Xiaomi / HyperOS:** allow Autostart, set battery to "No restrictions", and allow LightScout to override Do not
  disturb. See the [known limitations](limitations.md).
