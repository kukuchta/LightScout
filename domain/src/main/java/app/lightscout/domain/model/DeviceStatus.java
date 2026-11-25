package app.lightscout.domain.model;

import java.time.Instant;

/**
 * One uploader device status document (ENT-03, 03 §6.1).
 *
 * @param id       Nightscout {@code identifier}
 * @param time     status time (UTC)
 * @param iobCenti insulin on board in centi-units; nullable
 * @param device   device name
 */
public record DeviceStatus(String id, Instant time, Integer iobCenti, String device) {
}
