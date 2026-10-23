package com.darshan.ledger.interest;

import com.darshan.ledger.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

// The arithmetic of interest, kept apart from the job so it can be checked number by number.
// A day's interest is balance x annual rate / 365 and is kept at four decimals: a savings
// balance earns fractions of a paisa a day, and rounding each day to two decimals would lose
// most of a small account's interest before the month is summed. The sum is rounded to money
// only when it is posted. 365 is used for every year, leap years included, which is the
// convention the spec asks for; an actual/actual basis would pay 1/366 per day in a leap year.
final class DailyInterest {

    static final int ACCRUAL_SCALE = 4;
    static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);

    private DailyInterest() {
    }

    static BigDecimal of(BigDecimal balance, BigDecimal annualRate) {
        return balance.multiply(annualRate).divide(DAYS_IN_YEAR, ACCRUAL_SCALE, RoundingMode.HALF_EVEN);
    }

    static Money toMoney(BigDecimal accrued, Currency currency) {
        return Money.rounded(accrued, currency);
    }
}
