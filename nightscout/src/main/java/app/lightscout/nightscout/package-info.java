/**
 * Nightscout client of LightScout (CP-03, MOD-03): the only component that talks to Nightscout.
 *
 * <p>{@link app.lightscout.nightscout.OkHttpNightscoutGateway} implements the {@code :domain} port IF-07
 * {@link app.lightscout.domain.port.NightscoutGateway} over OkHttp 5 for the contracts IF-30…IF-41 (Nightscout API
 * v3 plus the v2 authorization endpoint). Everything else in this package is internal: transport rules (https only,
 * same-origin redirects, 8 MB cap — C-1, C-10, C-11), the in-memory JWT holder (C-2), tolerant mappers
 * (M-1…M-11, ADR-035) and the error classification into {@link app.lightscout.domain.port.FetchOutcome} (C-5).
 *
 * <p>Logging goes through the {@code :domain} port IF-66 only, with event codes and fixed-vocabulary details:
 * never a token, JWT, URL, host, query, body, value, free text or exception message (SR-07, C-9, C-14).
 */
package app.lightscout.nightscout;
