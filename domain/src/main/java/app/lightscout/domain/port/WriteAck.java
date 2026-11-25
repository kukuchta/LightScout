package app.lightscout.domain.port;

/**
 * Acknowledgement of a Nightscout write (03 §6.2, CP-00A R-40).
 *
 * @param id            {@code identifier} of the written document
 * @param srvModifiedMs server modification time in epoch ms ({@code -1} if unknown after an update, {@code 0} for a delete)
 * @param deduplicated  {@code true} when the server answered a create with {@code isDeduplication}
 */
public record WriteAck(String id, long srvModifiedMs, boolean deduplicated) {
}
