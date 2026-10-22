package com.darshan.ledger.statement;

import com.darshan.ledger.common.error.InvalidRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Objects;

// Both dates are inclusive, as on a printed statement; the instants are the half-open range the
// queries use, so a line posted at any time on the last day is in and midnight after it is out.
public record StatementPeriod(LocalDate from, LocalDate to, Instant start, Instant end) {

    public StatementPeriod {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
    }

    public static StatementPeriod of(LocalDate from, LocalDate to, Period maxPeriod, ZoneId zone) {
        if (from.isAfter(to)) {
            throw new InvalidRequestException(StatementErrorCode.INVALID_PERIOD,
                    "from " + from + " must not be after to " + to);
        }
        LocalDate latestEnd = from.plus(maxPeriod).minusDays(1);
        if (to.isAfter(latestEnd)) {
            throw new InvalidRequestException(StatementErrorCode.PERIOD_TOO_LONG,
                    "A statement from " + from + " may end no later than " + latestEnd);
        }
        return new StatementPeriod(from, to,
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
