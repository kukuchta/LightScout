# FAQ

### Does LightScout replace xDrip+, Dexcom, CareLink or my pump app?

No. LightScout reads what is already in your Nightscout and alarms on it. Keep your CGM app and its alarms. See the
[medical disclaimer](../DISCLAIMER.md).

### Which uploaders work?

Anything that puts readings into Nightscout 15.0.9 or newer: xDrip+, nightscout-connect (Dexcom Share, CareLink and
others), Loop, AndroidAPS and similar. LightScout does not talk to the CGM or the pump directly. See
[tested setups](tested-setups.md).

### The app says my Nightscout version is too old.

LightScout needs Nightscout **15.0.9 or newer**. Update Nightscout with your hosting's usual procedure. Older
versions have API problems LightScout cannot work around.

### The app refuses my token.

The token must allow reading and creating, updating and deleting treatments. Follow
[the token guide](nightscout-token.md) exactly. Do not use your `API_SECRET`.

### Why is my reading a few minutes old?

The age shown is the age of the newest reading in Nightscout. Most of it is the delay of the CGM and the uploader
(cloud followers such as Dexcom Share or CareLink add several minutes). LightScout checks Nightscout about once a
minute.

### An alarm did not ring. What should I check?

1. Open **Settings → Alarm readiness** and fix everything it lists.
2. Allow LightScout to override **Do not disturb** and check the **alarm volume**.
3. Exclude LightScout from **battery optimisation** and, on Xiaomi, enable **Autostart**. See
   [dontkillmyapp.com](https://dontkillmyapp.com/) for your phone.
4. Check that the phone had internet and that Nightscout had the reading at that time.

If it still happens, report it (see [SUPPORT.md](../SUPPORT.md)).

### I got an Urgent low alarm but the value looks impossible.

A Dexcom error code (values 1–12) uploaded as a reading looks like a very low glucose value to LightScout. Check
your CGM app. See [known limitations](limitations.md).

### Can two parents use LightScout?

Yes. Install it on each phone and give each phone its own token. Snoozing an alarm on one phone does not snooze
it on the other.

### Does LightScout work in mmol/L?

Not yet. It is planned.

### Where is my data stored?

On the phone and in your Nightscout only. Nothing is sent to the author. See [privacy](../PRIVACY.md).

### How do I update?

Download the new APK from [Releases](https://github.com/kukuchta/LightScout/releases) and install it over the existing app. Your settings stay.
