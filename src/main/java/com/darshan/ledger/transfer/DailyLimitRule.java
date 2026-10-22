package com.darshan.ledger.transfer;

import com.darshan.ledger.common.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// "Today" is the calendar day of the injected clock's zone, so the window is a business day,
// not a rolling 24 hours. The sum is read while the sender's balance row is locked, so two
// transfers racing for the last of the limit are counted one after the other.
@Component
@Order(70)
public class DailyLimitRule implements TransferRule {

    private final TransferRepository transferRepository;
    private final TransferLimitsProperties limits;
    private final Clock clock;

    public DailyLimitRule(TransferRepository transferRepository, TransferLimitsProperties limits, Clock clock) {
        this.transferRepository = transferRepository;
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    public TransferRuleResult evaluate(TransferContext context) {
        Currency currency = context.amount().currency();
        Instant startOfToday = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        Instant startOfTomorrow = LocalDate.now(clock).plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        BigDecimal sentToday = transferRepository
                .sumOutgoingBetween(context.sender().getId(), startOfToday, startOfTomorrow)
                .orElse(BigDecimal.ZERO);
        Money usedToday = Money.of(sentToday, currency);
        Money dailyLimit = Money.of(limits.daily(), currency);
        if (usedToday.plus(context.amount()).isGreaterThan(dailyLimit)) {
            return TransferRuleResult.rejected(TransferErrorCode.DAILY_LIMIT_EXCEEDED,
                    "Today's transfers of " + usedToday + " plus " + context.amount() + " exceed the daily limit of "
                            + dailyLimit);
        }
        return TransferRuleResult.allowed();
    }
}
