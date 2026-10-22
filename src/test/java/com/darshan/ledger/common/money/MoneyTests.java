package com.darshan.ledger.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTests {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");

    @ParameterizedTest
    @CsvSource({"10, 10.00", "10.5, 10.50", "1E+3, 1000.00", "0.10, 0.10", "-2.5, -2.50", "1.500, 1.50"})
    void ofNormalisesToTwoDecimalsWithoutLosingPrecision(String input, String expected) {
        Money money = Money.of(new BigDecimal(input), INR);

        assertThat(money.amount()).isEqualTo(new BigDecimal(expected));
        assertThat(money.amount().scale()).isEqualTo(2);
    }

    @Test
    void ofRejectsAmountsWithMoreThanTwoDecimals() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of(new BigDecimal("10.005"), INR))
                .withMessageContaining("more than 2 decimals");
    }

    @Test
    void constructorRejectsNegativeScale() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(new BigDecimal("1E+3"), INR))
                .withMessageContaining("scale 2");
    }

    @Test
    void constructorRejectsAnyScaleOtherThanTwo() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(new BigDecimal("10.5"), INR));
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(new BigDecimal("10.500"), INR));
    }

    @Test
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new Money(null, INR)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(new BigDecimal("1.00"), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalMoneyIsEqualRegardlessOfHowTheAmountWasWritten() {
        assertThat(Money.of("10", INR)).isEqualTo(Money.of("10.00", INR));
        assertThat(Money.of("10", INR)).hasSameHashCodeAs(Money.of("10.00", INR));
        assertThat(Money.of("10.00", INR)).isNotEqualTo(Money.of("10.00", USD));
    }

    @Test
    void addsAndSubtractsInTheSameCurrency() {
        Money sum = Money.of("100.10", INR).plus(Money.of("0.90", INR));
        Money difference = Money.of("100.00", INR).minus(Money.of("150.00", INR));

        assertThat(sum).isEqualTo(Money.of("101.00", INR));
        assertThat(difference).isEqualTo(Money.of("-50.00", INR));
        assertThat(difference.isNegative()).isTrue();
    }

    @Test
    void refusesToMixCurrencies() {
        Money rupees = Money.of("10.00", INR);
        Money dollars = Money.of("10.00", USD);

        assertThatThrownBy(() -> rupees.plus(dollars)).isInstanceOf(CurrencyMismatchException.class)
                .hasMessage("Cannot combine amounts in INR and USD");
        assertThatThrownBy(() -> rupees.minus(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> rupees.compareTo(dollars)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> rupees.isGreaterThan(dollars)).isInstanceOf(CurrencyMismatchException.class);
    }

    @ParameterizedTest
    @CsvSource({"0.25, 0.5, 0.12", "0.75, 0.5, 0.38", "1.07, 2.5, 2.68", "100.00, 0.0350, 3.50", "1.00, 0.004999, 0.00"})
    void multiplyRoundsHalfEven(String amount, String factor, String expected) {
        Money result = Money.of(amount, INR).multiply(new BigDecimal(factor));

        assertThat(result).isEqualTo(Money.of(expected, INR));
    }

    @Test
    void roundedAppliesHalfEvenToComputedValues() {
        assertThat(Money.rounded(new BigDecimal("10.005"), INR)).isEqualTo(Money.of("10.00", INR));
        assertThat(Money.rounded(new BigDecimal("10.015"), INR)).isEqualTo(Money.of("10.02", INR));
        assertThat(Money.rounded(new BigDecimal("10.0051"), INR)).isEqualTo(Money.of("10.01", INR));
    }

    @Test
    void comparesByAmount() {
        Money ten = Money.of("10.00", INR);
        Money twenty = Money.of("20.00", INR);

        assertThat(twenty.isGreaterThan(ten)).isTrue();
        assertThat(ten.isLessThan(twenty)).isTrue();
        assertThat(ten.compareTo(Money.of("10", INR))).isZero();
        assertThat(Money.zero(INR).isZero()).isTrue();
        assertThat(ten.isPositive()).isTrue();
        assertThat(ten.negate()).isEqualTo(Money.of("-10.00", INR));
    }

    @Test
    void toStringUsesPlainNotationAndTheCurrencyCode() {
        assertThat(Money.of(new BigDecimal("1E+3"), INR)).hasToString("1000.00 INR");
        assertThat(Money.zero(USD)).hasToString("0.00 USD");
    }
}
