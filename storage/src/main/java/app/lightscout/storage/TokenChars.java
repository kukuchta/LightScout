package app.lightscout.storage;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * UTF-8 conversion between the token {@code char[]} of the ports (SR-02: short-lived plaintext, no
 * {@code String}) and the bytes the cipher needs. Every intermediate buffer is wiped.
 */
final class TokenChars {

    private TokenChars() {
    }

    /**
     * @param chars token characters; not modified, not retained
     * @return UTF-8 bytes; the caller wipes them
     * @throws CharacterCodingException for unpaired surrogates
     */
    static byte[] encode(char[] chars) throws CharacterCodingException {
        ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(chars));
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return out;
    }

    /**
     * @param bytes UTF-8 bytes; not modified, not retained
     * @return token characters; the caller wipes them
     * @throws CharacterCodingException for malformed UTF-8
     */
    static char[] decode(byte[] bytes) throws CharacterCodingException {
        CharBuffer buffer = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        char[] out = new char[buffer.remaining()];
        buffer.get(out);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
        return out;
    }
}
