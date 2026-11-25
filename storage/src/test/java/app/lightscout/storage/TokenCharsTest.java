package app.lightscout.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** UTF-8 conversion of the token without {@code String} (SR-02). */
public class TokenCharsTest {

    @Test
    public void asciiAndNonAsciiRoundTrip() throws Exception {
        char[] token = "fake-token-żółć-€".toCharArray();
        byte[] bytes = TokenChars.encode(token);
        assertArrayEquals("fake-token-żółć-€".getBytes(StandardCharsets.UTF_8), bytes);
        assertArrayEquals(token, TokenChars.decode(bytes));
    }

    @Test
    public void emptyTokenRoundTrips() throws Exception {
        assertArrayEquals(new char[0], TokenChars.decode(TokenChars.encode(new char[0])));
    }

    @Test
    public void malformedInputIsReported() {
        assertThrows(CharacterCodingException.class, () -> TokenChars.decode(new byte[] {(byte) 0xC3}));
        assertThrows(CharacterCodingException.class, () -> TokenChars.encode(new char[] {'\uD800'}));
    }
}
