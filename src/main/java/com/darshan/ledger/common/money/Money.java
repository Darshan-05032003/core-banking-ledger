package com.darshan.ledger.common.money;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Immutable amount in one currency. The amount always has exactly two decimals, so equals and
// hashCode behave the same as compareTo, and every rounding happens in one place with HALF_EVEN.
@Embeddable
public record Money(
        @Column(name = "amount", nullable = false, precision = 19, scale = 2) BigDecimal amount,
        @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency", nullable = false, length = 3) Currency currency)
        implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.scale() != SCALE) {
            throw new IllegalArgumentException("Money amount must have scale " + SCALE + " but was " + amount.scale());
        }
    }

    // Accepts any representation that fits in two decimals (10, 10.5, 1E+3) and rejects values
    // that would silently lose precision (10.005). Values produced by calculations go through
    // rounded() instead, where the rounding is an explicit decision.
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("Money amount " + amount + " has more than " + SCALE + " decimals");
        }
        return new Money(amount.setScale(SCALE, ROUNDING), currency);
    }

    public static Money of(String amount, Currency currency) {
        return of(new BigDecimal(amount), currency);
    }

    public static Money rounded(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        return new Money(amount.setScale(SCALE, ROUNDING), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO.setScale(SCALE), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        return rounded(amount.multiply(factor), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean hasCurrency(Currency expected) {
        return currency.equals(expected);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
