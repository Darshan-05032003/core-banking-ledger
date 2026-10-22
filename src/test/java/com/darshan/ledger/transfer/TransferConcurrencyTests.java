package com.darshan.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.account.AccountService;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.idempotency.IdempotencyErrorCode;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.ledger.LedgerErrorCode;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

// Transfers are driven through the service rather than MockMvc so that the only thing under test
// is the locking; requests run as "system" because the worker threads carry no security context.
@IntegrationTest
class TransferConcurrencyTests {

    private static final Currency INR = Currency.getInstance("INR");
    private static final int THREADS = 16;

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TestAccounts testAccounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aHundredConcurrentTransfersConserveMoneyAndNeverOverdraw() throws Exception {
        long a = testAccounts.openFundedAccount("10000.00");
        long b = testAccounts.openFundedAccount("10000.00");
        String fromA = testAccounts.accountNumber(a);
        String toB = testAccounts.accountNumber(b);
        List<Callable<Object>> tasks = new ArrayList<>();
        BigDecimal expectedMoved = BigDecimal.ZERO;
        for (int i = 0; i < 100; i++) {
            BigDecimal amount = new BigDecimal(10 * (i % 7 + 1) + ".00");
            expectedMoved = expectedMoved.add(amount);
            tasks.add(() -> transferService.transfer(UUID.randomUUID().toString(), request(fromA, toB, amount)));
        }

        List<Object> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(TransferOutcome.Created.class::isInstance);
        Money balanceA = available(a);
        Money balanceB = available(b);
        assertThat(balanceA.plus(balanceB)).isEqualTo(Money.of("20000.00", INR));
        assertThat(balanceA).isEqualTo(Money.of("10000.00", INR).minus(Money.of(expectedMoved, INR)));
        assertThat(balanceB).isEqualTo(Money.of("10000.00", INR).plus(Money.of(expectedMoved, INR)));
        assertThat(balanceA.isNegative()).isFalse();
        assertThat(countTransfers(a, TransferStatus.COMPLETED)).isEqualTo(100);
        assertThat(countJournalsFrom(a)).isEqualTo(100);
    }

    @Test
    void oppositeTransfersBetweenTheSameAccountsFinishWithoutDeadlock() throws Exception {
        long a = testAccounts.openFundedAccount("5000.00");
        long b = testAccounts.openFundedAccount("5000.00");
        String numberA = testAccounts.accountNumber(a);
        String numberB = testAccounts.accountNumber(b);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            boolean aToB = i % 2 == 0;
            BigDecimal amount = new BigDecimal("25.00");
            tasks.add(() -> transferService.transfer(UUID.randomUUID().toString(),
                    aToB ? request(numberA, numberB, amount) : request(numberB, numberA, amount)));
        }

        Instant started = Instant.now();
        List<Object> outcomes = runConcurrently(tasks);

        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(60));
        assertThat(outcomes).allMatch(TransferOutcome.Created.class::isInstance);
        assertThat(outcomes).noneMatch(Throwable.class::isInstance);
        assertThat(available(a)).isEqualTo(Money.of("5000.00", INR));
        assertThat(available(b)).isEqualTo(Money.of("5000.00", INR));
        assertThat(countTransfers(a, TransferStatus.COMPLETED) + countTransfers(b, TransferStatus.COMPLETED))
                .isEqualTo(100);
    }

    @Test
    void fiftyTransfersRacingForABalanceThatCoversTenLetExactlyTenThrough() throws Exception {
        long a = testAccounts.openFundedAccount("100.00");
        long b = testAccounts.openActiveAccount();
        String fromA = testAccounts.accountNumber(a);
        String toB = testAccounts.accountNumber(b);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> transferService.transfer(UUID.randomUUID().toString(),
                    request(fromA, toB, new BigDecimal("10.00"))));
        }

        List<Object> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(TransferOutcome.Created.class::isInstance).hasSize(10);
        assertThat(outcomes).filteredOn(TransferRejectedException.class::isInstance).hasSize(40)
                .allSatisfy(ex -> {
                    TransferRejectedException rejection = (TransferRejectedException) ex;
                    assertThat(rejection.getErrorCode()).isEqualTo(LedgerErrorCode.INSUFFICIENT_FUNDS);
                    assertThat(rejection.transferReference()).isPresent();
                });
        assertThat(available(a)).isEqualTo(Money.zero(INR));
        assertThat(available(b)).isEqualTo(Money.of("100.00", INR));
        assertThat(countTransfers(a, TransferStatus.COMPLETED)).isEqualTo(10);
        assertThat(countTransfers(a, TransferStatus.FAILED)).isEqualTo(40);
        assertThat(countJournalsFrom(a)).isEqualTo(10);
    }

    @Test
    void concurrentRequestsWithTheSameKeyProduceExactlyOneTransfer() throws Exception {
        long a = testAccounts.openFundedAccount("1000.00");
        long b = testAccounts.openActiveAccount();
        TransferRequest request = request(testAccounts.accountNumber(a), testAccounts.accountNumber(b),
                new BigDecimal("40.00"));
        String key = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> transferService.transfer(key, request));
        }

        List<Object> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(TransferOutcome.Created.class::isInstance).hasSize(1);
        assertThat(outcomes).allSatisfy(outcome -> {
            if (outcome instanceof ConflictException conflict) {
                assertThat(conflict.getErrorCode()).isEqualTo(IdempotencyErrorCode.REQUEST_IN_PROGRESS);
            } else {
                assertThat(outcome).isInstanceOf(TransferOutcome.class);
            }
        });
        assertThat(outcomes).filteredOn(TransferOutcome.Replayed.class::isInstance)
                .allSatisfy(replayed -> assertThat(((TransferOutcome.Replayed) replayed).response().status())
                        .isEqualTo(201));
        assertThat(available(a)).isEqualTo(Money.of("960.00", INR));
        assertThat(available(b)).isEqualTo(Money.of("40.00", INR));
        assertThat(countTransfers(a, TransferStatus.COMPLETED)).isEqualTo(1);
        assertThat(countJournalsFrom(a)).isEqualTo(1);
    }

    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes).noneMatch(TransferConcurrencyTests::isLockFailure);
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    // 40P01 is deadlock_detected and 55P03 is lock_not_available: neither may ever surface.
    private static boolean isLockFailure(Object outcome) {
        Throwable cause = outcome instanceof Throwable throwable ? throwable : null;
        while (cause != null) {
            if (cause instanceof SQLException sql && ("40P01".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static TransferRequest request(String from, String to, BigDecimal amount) {
        return new TransferRequest(from, to, amount, "INR", null);
    }

    private Money available(long accountId) {
        return Money.of(accountService.balance(accountId).availableBalance(), INR);
    }

    private long countTransfers(long fromAccountId, TransferStatus status) {
        return transferRepository.findAll().stream()
                .filter(t -> t.getFromAccount().getId() == fromAccountId && t.getStatus() == status)
                .count();
    }

    private Integer countJournalsFrom(long accountId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from ledger_entry e join journal_entry j on j.id = e.journal_entry_id
                where j.type = 'TRANSFER' and e.account_id = ? and e.direction = 'DEBIT'
                """, Integer.class, accountId);
    }
}
