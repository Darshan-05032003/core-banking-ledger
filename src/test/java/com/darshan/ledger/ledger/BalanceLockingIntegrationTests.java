package com.darshan.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.darshan.ledger.IntegrationTest;
import com.darshan.ledger.TestAccounts;
import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountErrorCode;
import com.darshan.ledger.account.AccountRepository;
import com.darshan.ledger.account.AccountService;
import com.darshan.ledger.account.AccountStatus;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.LedgerException;
import com.darshan.ledger.common.money.Money;
import com.darshan.ledger.transfer.TransferOutcome;
import com.darshan.ledger.transfer.TransferRequest;
import com.darshan.ledger.transfer.TransferService;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class BalanceLockingIntegrationTests {

    private static final Currency INR = Currency.getInstance("INR");

    @Autowired
    private PostingService postingService;

    @Autowired
    private AccountBalanceRepository accountBalanceRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private GlAccountRepository glAccountRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private LedgerProperties ledgerProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestAccounts testAccounts;

    @Test
    void theLockTimeoutIsSetForTheLockingTransactionOnly() {
        long accountId = testAccounts.openActiveAccount();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        String insideTransaction = transaction.execute(status -> {
            postingService.lockBalances(List.of(accountRepository.findById(accountId).orElseThrow()));
            return jdbcTemplate.queryForObject("select current_setting('lock_timeout')", String.class);
        });

        assertThat(ledgerProperties.lockTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(insideTransaction).isEqualTo("2s");
        assertThat(jdbcTemplate.queryForObject("select current_setting('lock_timeout')", String.class)).isEqualTo("0");
    }

    @Test
    void lockingBalancesOutsideATransactionIsRefused() {
        long accountId = testAccounts.openActiveAccount();
        Account account = accountRepository.findById(accountId).orElseThrow();

        assertThatThrownBy(() -> postingService.lockBalances(List.of(account)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void aTransferWaitingOnAHeldRowGivesUpAfterTheLockTimeoutAndCanBeRetried() throws Exception {
        long senderId = testAccounts.openFundedAccount("100.00");
        long beneficiaryId = testAccounts.openActiveAccount();
        TransferRequest request = new TransferRequest(testAccounts.accountNumber(senderId),
                testAccounts.accountNumber(beneficiaryId), new BigDecimal("10.00"), "INR", null);
        String key = UUID.randomUUID().toString();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                accountBalanceRepository.lockByAccountId(senderId).orElseThrow();
                locked.countDown();
                await(release);
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Instant started = Instant.now();
            assertThatThrownBy(() -> transferService.transfer(key, request))
                    .isInstanceOf(PessimisticLockingFailureException.class)
                    .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("55P03"));
            Duration waited = Duration.between(started, Instant.now());
            assertThat(waited).isGreaterThanOrEqualTo(Duration.ofSeconds(2)).isLessThan(Duration.ofSeconds(10));

            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }

        assertThat(transferService.transfer(key, request)).isInstanceOf(TransferOutcome.Created.class);
        assertThat(accountService.balance(senderId).availableBalance()).isEqualByComparingTo("90.00");
    }

    @Test
    void aDepositThatLoadedTheAccountBeforeItWasClosedIsRefusedOnceItGetsTheLock() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        CountDownLatch accountLoaded = new CountDownLatch(1);
        CountDownLatch accountClosed = new CountDownLatch(1);
        ExecutorService depositor = Executors.newSingleThreadExecutor();
        try {
            Future<?> deposit = depositor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                Account account = accountRepository.findById(accountId).orElseThrow();
                assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
                accountLoaded.countDown();
                await(accountClosed);
                Money amount = Money.of("500.00", INR);
                GlAccount vault = glAccountRepository.require(GlAccountCode.CASH_VAULT);
                return postingService.post(Posting.of(JournalType.DEPOSIT, "Late deposit",
                        List.of(PostingLeg.debit(vault, amount), PostingLeg.credit(account, amount))));
            }));
            assertThat(accountLoaded.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(accountService.close(accountId).status()).isEqualTo(AccountStatus.CLOSED);
            accountClosed.countDown();

            assertThatThrownBy(() -> deposit.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ConflictException.class)
                    .satisfies(ex -> assertThat(((LedgerException) ex.getCause()).getErrorCode())
                            .isEqualTo(AccountErrorCode.ACCOUNT_CLOSED));
        } finally {
            accountClosed.countDown();
            depositor.shutdownNow();
        }

        assertThat(accountService.balance(accountId).ledgerBalance()).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject("select count(*) from ledger_entry where account_id = ?", Integer.class,
                accountId)).isZero();
    }

    @Test
    void closingWaitsForAPostingThatHoldsTheBalanceRowAndThenSeesTheNewBalance() throws Exception {
        long accountId = testAccounts.openActiveAccount();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch finishDeposit = new CountDownLatch(1);
        ExecutorService depositor = Executors.newSingleThreadExecutor();
        try {
            Future<?> deposit = depositor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                Account account = accountRepository.findById(accountId).orElseThrow();
                Money amount = Money.of("20.00", INR);
                GlAccount vault = glAccountRepository.require(GlAccountCode.CASH_VAULT);
                JournalEntry journal = postingService.post(Posting.of(JournalType.DEPOSIT, "Slow deposit",
                        List.of(PostingLeg.debit(vault, amount), PostingLeg.credit(account, amount))));
                rowLocked.countDown();
                await(finishDeposit);
                return journal;
            }));
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                Future<?> close = closer.submit(() -> accountService.close(accountId));
                assertThat(close.isDone()).isFalse();
                finishDeposit.countDown();
                deposit.get(30, TimeUnit.SECONDS);
                assertThatThrownBy(() -> close.get(30, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(LedgerException.class)
                        .satisfies(ex -> assertThat(((LedgerException) ex.getCause()).getErrorCode())
                                .isEqualTo(AccountErrorCode.BALANCE_NOT_ZERO));
            } finally {
                closer.shutdownNow();
            }
        } finally {
            finishDeposit.countDown();
            depositor.shutdownNow();
        }

        assertThat(accountRepository.findById(accountId).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(accountService.balance(accountId).ledgerBalance()).isEqualByComparingTo("20.00");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other thread");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static String sqlState(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
