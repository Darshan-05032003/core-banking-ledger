package com.darshan.ledger.interest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DailyInterestTests {

    private static final Currency INR = Currency.getInstance("INR");

    // At 3.65% a day's interest is exactly balance / 10000, which makes the half-way cases easy
    // to read: 1234.50 gives 0.12345, which HALF_EVEN rounds down to the even 4 and HALF_UP
    // would have rounded up to 0.1235.
    @ParameterizedTest(name = "{0} at {1} earns {2} a day")
    @CsvSource({
            "100000.00, 0.0350, 9.5890",
            "250000.00, 0.0350, 23.9726",
            "1.00,      0.0350, 0.0001",
            "0.00,      0.0350, 0.0000",
            "1234.50,   0.0365, 0.1234",
            "1235.50,   0.0365, 0.1236",
            "1234.70,   0.0365, 0.1235",
            "999999999999.99, 0.0700, 191780821.9178"
    })
    void aDaysInterestIsBalanceTimesRateOver365RoundedHalfEvenToFourDecimals(String balance, String rate,
            String expected) {
        BigDecimal accrued = DailyInterest.of(new BigDecimal(balance), new BigDecimal(rate));

        assertThat(accrued.scale()).isEqualTo(4);
        assertThat(accrued).isEqualByComparingTo(expected);
        assertThat(accrued.toPlainString()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} accrued is posted as {1}")
    @CsvSource({
            "12.3450, 12.34",
            "12.3550, 12.36",
            "12.3451, 12.35",
            "0.0049,  0.00",
            "0.0050,  0.00",
            "0.0051,  0.01"
    })
    void theMonthsSumIsRoundedHalfEvenToMoneyWhenPosted(String accrued, String expected) {
        assertThat(DailyInterest.toMoney(new BigDecimal(accrued), INR).amount()).isEqualTo(new BigDecimal(expected));
    }
}
