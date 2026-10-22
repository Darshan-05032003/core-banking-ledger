package com.darshan.ledger.transfer;

import static com.darshan.ledger.transfer.TransferFixtures.INR;
import static com.darshan.ledger.transfer.TransferFixtures.LIMITS;
import static com.darshan.ledger.transfer.TransferFixtures.NOW;
import static com.darshan.ledger.transfer.TransferFixtures.TODAY;
import static com.darshan.ledger.transfer.TransferFixtures.USD;
import static com.darshan.ledger.transfer.TransferFixtures.context;
import static com.darshan.ledger.transfer.TransferFixtures.request;
import static com.darshan.ledger.transfer.TransferFixtures.unverifiedAccount;
import static com.darshan.ledger.transfer.TransferFixtures.verifiedAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountErrorCode;
import com.darshan.ledger.common.error.ErrorCode;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.ledger.LedgerErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransferRulesTests {

    private static final Account SENDER = verifiedAccount(2L, INR);
    private static final Account BENEFICIARY = verifiedAccount(5L, INR);

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void accountsExistRule(String name, TransferContext context, ErrorCode expected) {
        assertOutcome(new AccountsExistRule().evaluate(context), expected);
    }

    static Stream<Arguments> accountsExistRule() {
        TransferRequest request = request(SENDER, BENEFICIARY, "10.00", "INR");
        Money amount = Money.of("10.00", INR);
        return Stream.of(
                Arguments.of("both accounts resolved", context(SENDER, BENEFICIARY, "10.00"), null),
                Arguments.of("unknown sender", new TransferContext(request, amount, null, BENEFICIARY, null),
                        TransferErrorCode.ACCOUNT_NOT_FOUND),
                Arguments.of("unknown beneficiary", new TransferContext(request, amount, SENDER, null, null),
                        TransferErrorCode.ACCOUNT_NOT_FOUND));
    }

    @Test
    void accountsExistRuleMasksTheNumberInItsMessage() {
        TransferRequest request = request(SENDER, BENEFICIARY, "10.00", "INR");
        TransferRuleResult.Rejected rejected = (TransferRuleResult.Rejected) new AccountsExistRule()
                .evaluate(new TransferContext(request, Money.of("10.00", INR), SENDER, null, null));

        assertThat(rejected.message()).contains("XXXXXXXX" + request.toAccount().substring(8))
                .doesNotContain(request.toAccount());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void distinctAccountsRule(String name, TransferContext context, ErrorCode expected) {
        assertOutcome(new DistinctAccountsRule().evaluate(context), expected);
    }

    static Stream<Arguments> distinctAccountsRule() {
        return Stream.of(
                Arguments.of("two accounts", context(SENDER, BENEFICIARY, "10.00"), null),
                Arguments.of("same account twice", context(SENDER, SENDER, "10.00"), TransferErrorCode.SAME_ACCOUNT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void accountsActiveRule(String name, TransferContext context, ErrorCode expected) {
        assertOutcome(new AccountsActiveRule().evaluate(context), expected);
    }

    static Stream<Arguments> accountsActiveRule() {
        Account frozenSender = verifiedAccount(2L, INR);
        frozenSender.freeze();
        Account closedSender = verifiedAccount(2L, INR);
        closedSender.close(TODAY);
        Account frozenBeneficiary = verifiedAccount(5L, INR);
        frozenBeneficiary.freeze();
        Account closedBeneficiary = verifiedAccount(5L, INR);
        closedBeneficiary.close(TODAY);
        return Stream.of(
                Arguments.of("both active", context(SENDER, BENEFICIARY, "10.00"), null),
                Arguments.of("frozen sender", context(frozenSender, BENEFICIARY, "10.00"),
                        AccountErrorCode.ACCOUNT_NOT_ACTIVE),
                Arguments.of("closed sender", context(closedSender, BENEFICIARY, "10.00"),
                        AccountErrorCode.ACCOUNT_CLOSED),
                Arguments.of("frozen beneficiary", context(SENDER, frozenBeneficiary, "10.00"),
                        AccountErrorCode.ACCOUNT_NOT_ACTIVE),
                Arguments.of("closed beneficiary", context(SENDER, closedBeneficiary, "10.00"),
                        AccountErrorCode.ACCOUNT_CLOSED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void sameCurrencyRule(String name, TransferContext context, ErrorCode expected) {
        assertOutcome(new SameCurrencyRule().evaluate(context), expected);
    }

    static Stream<Arguments> sameCurrencyRule() {
        Account dollarSender = verifiedAccount(2L, USD);
        Account dollarBeneficiary = verifiedAccount(5L, USD);
        return Stream.of(
                Arguments.of("everything in INR", context(SENDER, BENEFICIARY, "10.00", "INR", "100.00"), null),
                Arguments.of("sender holds USD", context(dollarSender, BENEFICIARY, "10.00", "INR", "100.00"),
                        LedgerErrorCode.CURRENCY_MISMATCH),
                Arguments.of("beneficiary holds USD", context(SENDER, dollarBeneficiary, "10.00", "INR", "100.00"),
                        LedgerErrorCode.CURRENCY_MISMATCH),
                Arguments.of("request in USD between INR accounts",
                        new TransferContext(request(SENDER, BENEFICIARY, "10.00", "USD"), Money.of("10.00", USD),
                                SENDER, BENEFICIARY, null),
                        LedgerErrorCode.CURRENCY_MISMATCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void senderKycVerifiedRule(String name, TransferContext context, ErrorCode expected) {
        assertOutcome(new SenderKycVerifiedRule().evaluate(context), expected);
    }

    static Stream<Arguments> senderKycVerifiedRule() {
        Account pendingSender = unverifiedAccount(2L, INR);
        Account rejectedSender = unverifiedAccount(2L, INR);
        rejectedSender.getCustomer().rejectKyc("Document expired", NOW);
        Account pendingBeneficiary = unverifiedAccount(5L, INR);
        return Stream.of(
                Arguments.of("verified sender", context(SENDER, BENEFICIARY, "10.00"), null),
                Arguments.of("pending sender", context(pendingSender, BENEFICIARY, "10.00"),
                        AccountErrorCode.CUSTOMER_NOT_VERIFIED),
                Arguments.of("rejected sender", context(rejectedSender, BENEFICIARY, "10.00"),
                        AccountErrorCode.CUSTOMER_NOT_VERIFIED),
                Arguments.of("unverified beneficiary may still receive", context(SENDER, pendingBeneficiary, "10.00"),
                        null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void amountWithinLimitRule(String name, TransferContext context, ErrorCode expected) {
        assertOutcome(new AmountWithinLimitRule(LIMITS).evaluate(context), expected);
    }

    static Stream<Arguments> amountWithinLimitRule() {
        return Stream.of(
                Arguments.of("small amount", context(SENDER, BENEFICIARY, "1.00"), null),
                Arguments.of("exactly the limit", context(SENDER, BENEFICIARY, "500000.00"), null),
                Arguments.of("one paisa over the limit", context(SENDER, BENEFICIARY, "500000.01"),
                        TransferErrorCode.PER_TRANSACTION_LIMIT_EXCEEDED),
                Arguments.of("zero", context(SENDER, BENEFICIARY, "0.00"), TransferErrorCode.INVALID_AMOUNT),
                Arguments.of("negative", context(SENDER, BENEFICIARY, "-5.00"), TransferErrorCode.INVALID_AMOUNT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void dailyLimitRule(String name, String sentToday, String amount, ErrorCode expected) {
        TransferRepository repository = mock(TransferRepository.class);
        given(repository.sumOutgoingBetween(eq(2L), eq(Instant.parse("2026-10-22T00:00:00Z")),
                eq(Instant.parse("2026-10-23T00:00:00Z"))))
                .willReturn(Optional.ofNullable(sentToday).map(BigDecimal::new));
        DailyLimitRule rule = new DailyLimitRule(repository, LIMITS, Clock.fixed(NOW, ZoneOffset.UTC));

        assertOutcome(rule.evaluate(context(SENDER, BENEFICIARY, amount)), expected);
    }

    static Stream<Arguments> dailyLimitRule() {
        return Stream.of(
                Arguments.of("nothing sent yet", null, "500000.00", null),
                Arguments.of("exactly reaching the limit", "1500000.00", "500000.00", null),
                Arguments.of("one paisa over the limit", "1500000.01", "500000.00",
                        TransferErrorCode.DAILY_LIMIT_EXCEEDED),
                Arguments.of("limit already used up", "2000000.00", "0.01", TransferErrorCode.DAILY_LIMIT_EXCEEDED));
    }

    @Test
    void dailyLimitResetsAtMidnightOfTheClocksZone() {
        TransferRepository repository = mock(TransferRepository.class);
        given(repository.sumOutgoingBetween(anyLong(), eq(Instant.parse("2026-10-22T00:00:00Z")),
                eq(Instant.parse("2026-10-23T00:00:00Z")))).willReturn(Optional.of(new BigDecimal("1999999.50")));
        given(repository.sumOutgoingBetween(anyLong(), eq(Instant.parse("2026-10-23T00:00:00Z")),
                eq(Instant.parse("2026-10-24T00:00:00Z")))).willReturn(Optional.empty());
        TransferContext context = context(SENDER, BENEFICIARY, "1.00");

        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-10-22T23:59:59Z"), ZoneOffset.UTC);
        assertOutcome(new DailyLimitRule(repository, LIMITS, beforeMidnight).evaluate(context),
                TransferErrorCode.DAILY_LIMIT_EXCEEDED);

        Clock afterMidnight = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC);
        assertOutcome(new DailyLimitRule(repository, LIMITS, afterMidnight).evaluate(context), null);
    }

    @Test
    void dailyLimitUsesTheBusinessDayOfTheClocksZoneNotUtc() {
        TransferRepository repository = mock(TransferRepository.class);
        given(repository.sumOutgoingBetween(anyLong(), eq(Instant.parse("2026-10-22T18:30:00Z")),
                eq(Instant.parse("2026-10-23T18:30:00Z")))).willReturn(Optional.empty());
        Clock kolkataJustAfterMidnight = Clock.fixed(Instant.parse("2026-10-22T20:00:00Z"), ZoneId.of("Asia/Kolkata"));

        assertOutcome(new DailyLimitRule(repository, LIMITS, kolkataJustAfterMidnight)
                .evaluate(context(SENDER, BENEFICIARY, "1.00")), null);

        verify(repository).sumOutgoingBetween(2L, Instant.parse("2026-10-22T18:30:00Z"),
                Instant.parse("2026-10-23T18:30:00Z"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void sufficientBalanceRule(String name, TransferContext context, ErrorCode expected) {
        assertOutcome(new SufficientBalanceRule().evaluate(context), expected);
    }

    static Stream<Arguments> sufficientBalanceRule() {
        return Stream.of(
                Arguments.of("more than enough", context(SENDER, BENEFICIARY, "10.00", "INR", "10.01"), null),
                Arguments.of("exactly enough", context(SENDER, BENEFICIARY, "10.00", "INR", "10.00"), null),
                Arguments.of("one paisa short", context(SENDER, BENEFICIARY, "10.00", "INR", "9.99"),
                        LedgerErrorCode.INSUFFICIENT_FUNDS),
                Arguments.of("empty account", context(SENDER, BENEFICIARY, "10.00", "INR", "0.00"),
                        LedgerErrorCode.INSUFFICIENT_FUNDS));
    }

    @Test
    void sufficientBalanceRuleRefusesToRunWithoutALockedBalance() {
        TransferContext unlocked = new TransferContext(request(SENDER, BENEFICIARY, "10.00", "INR"),
                Money.of("10.00", INR), SENDER, BENEFICIARY, null);

        assertThatThrownBy(() -> new SufficientBalanceRule().evaluate(unlocked))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertOutcome(TransferRuleResult result, ErrorCode expected) {
        if (expected == null) {
            assertThat(result).isInstanceOf(TransferRuleResult.Allowed.class);
        } else {
            assertThat(result).isInstanceOf(TransferRuleResult.Rejected.class);
            assertThat(((TransferRuleResult.Rejected) result).errorCode()).isEqualTo(expected);
            assertThat(((TransferRuleResult.Rejected) result).message()).isNotBlank();
        }
    }
}
