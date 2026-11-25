/**
 * LightScout pure-Java domain core ({@code :domain}, CP-01, MOD-01).
 *
 * <p>Holds the domain value types, the safety rules as pure functions and every port interface.
 * JDK only (CP-00 R-1): no Android, no third-party library, no I/O, no logging, no clock reads.
 * Time is always a parameter. Results are enums, codes and numbers, never user-visible strings.
 */
package app.lightscout.domain;
