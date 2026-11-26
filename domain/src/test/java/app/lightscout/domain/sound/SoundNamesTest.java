package app.lightscout.domain.sound;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** DCR-51: the name shown for a user file is cleaned once, here, before it reaches a list row or a log (never logged). */
public class SoundNamesTest {

    @Test
    public void theExtensionIsRemoved() {
        assertEquals("Morning", SoundNames.displayName("Morning.mp3", 1));
        assertEquals("a.b", SoundNames.displayName("a.b.m4a", 1));
        assertEquals("Wake up", SoundNames.displayName("Wake up.flac", 1));
    }

    @Test
    public void aNameWithoutAnExtensionStaysAsItIs() {
        assertEquals("Siren", SoundNames.displayName("Siren", 1));
        assertEquals("v1.2 siren", SoundNames.displayName("v1.2 siren", 1));
    }

    @Test
    public void controlCharactersAndOuterSpacesAreRemoved() {
        assertEquals("ab", SoundNames.displayName("a\u0007b.mp3", 1));
        assertEquals("x", SoundNames.displayName("  x  .wav", 1));
        assertEquals("a b", SoundNames.displayName("a\n\tb.ogg", 1));
    }

    @Test
    public void aLongNameIsCutAtFortyCharacters() {
        String cut = SoundNames.displayName("a".repeat(100) + ".mp3", 1);
        assertEquals(40, cut.length());
    }

    @Test
    public void anEmptyOrMissingNameBecomesSoundN() {
        assertEquals("Sound 3", SoundNames.displayName(null, 3));
        assertEquals("Sound 3", SoundNames.displayName("", 3));
        assertEquals("Sound 2", SoundNames.displayName("   ", 2));
        assertEquals("Sound 4", SoundNames.displayName(".mp3", 4));
        assertEquals("Sound 1", SoundNames.displayName("\u0001\u0002", 1));
    }

    @Test
    public void textDirectionOverridesAndInvisibleCharactersAreRemoved() {
        assertEquals("abc", SoundNames.displayName("a‮b‬c.mp3", 1));
        assertEquals("ab", SoundNames.displayName("a​b", 1));
        assertEquals("ab", SoundNames.displayName("a⁦b⁩", 1));
        assertEquals("ab", SoundNames.displayName("a b", 1) .replace(" ", ""));
        assertEquals("ab", SoundNames.displayName("﻿ab", 1));
    }

    @Test
    public void theResultIsAlwaysAValidDisplayName() {
        for (String raw : new String[] {null, "", "x", "Ünïcödé.mp3", "a".repeat(500), "\u0000", "..", "a/b.mp3",
                "a\u0085b", "\u009f", "😀".repeat(30)}) {
            String name = SoundNames.displayName(raw, 1);
            // OwnSound validates the name; it must never refuse a cleaned one
            new OwnSound("0123456789abcdef", name, "own-0123456789abcdef", 10);
        }
    }
}
