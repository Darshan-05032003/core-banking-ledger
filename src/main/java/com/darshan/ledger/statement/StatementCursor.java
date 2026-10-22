package com.darshan.ledger.statement;

import com.darshan.ledger.common.error.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

// The position of the last line a page returned, as the keyset the next page continues after.
// The token is opaque to clients: its layout may change, and a line's time and id say nothing a
// client should build on.
public record StatementCursor(Instant postedAt, long entryId) {

    private static final char SEPARATOR = '|';

    public static StatementCursor after(StatementLine line) {
        return new StatementCursor(line.postedAt(), line.entryId());
    }

    public String encode() {
        String plain = postedAt + String.valueOf(SEPARATOR) + entryId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public static StatementCursor decode(String token) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = plain.lastIndexOf(SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new StatementCursor(Instant.parse(plain.substring(0, separator)),
                    Long.parseLong(plain.substring(separator + 1)));
        } catch (RuntimeException ex) {
            throw new InvalidRequestException(StatementErrorCode.INVALID_CURSOR,
                    "cursor is not a token returned by a previous statement page");
        }
    }
}
