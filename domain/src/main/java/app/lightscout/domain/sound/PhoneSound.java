package app.lightscout.domain.sound;

/**
 * An alarm sound the phone offers (DCR-51).
 *
 * @param uri   content URI to copy from
 * @param title title as the phone names it
 */
public record PhoneSound(String uri, String title) {
}
