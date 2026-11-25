# Security

LightScout handles a Nightscout access token and health data, and its alarms must be reliable. Security problems
are taken seriously.

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately with GitHub's
[private vulnerability reporting](https://github.com/kukuchta/LightScout/security/advisories/new).

Include what you found, how to reproduce it, the LightScout version and what an attacker could do. Do not include
real tokens, addresses or health data.

You will get an answer as soon as possible. This is a one-person hobby project, so allow some time. Please do not
disclose the problem publicly until a fixed version is released or we agree on a date.

## Supported versions

Only the latest release gets fixes.

## Good practice for users

- Use an access token with only the roles described in [the token guide](docs/nightscout-token.md). Never put your
  `API_SECRET` into any phone app.
- Install LightScout only from this repository's [Releases](https://github.com/kukuchta/LightScout/releases) and
  compare the SHA-256 checksum. Android refuses updates signed with a different key; if it does, do not force it.
- If a phone with LightScout is lost, delete its token in Nightscout (see the token guide).
