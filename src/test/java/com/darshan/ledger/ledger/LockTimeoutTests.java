package com.darshan.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LockTimeoutTests {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final LockTimeout lockTimeout = new LockTimeout(jdbcTemplate,
            new LedgerProperties(Duration.ofSeconds(3), "0 0 1 * * *"));

    @Test
    void outsideATransactionItRefusesInsteadOfIssuingAStatementPostgresWouldIgnore() {
        assertThatThrownBy(lockTimeout::applyToCurrentTransaction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void insideATransactionItSetsTheConfiguredTimeoutInMilliseconds() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            lockTimeout.applyToCurrentTransaction();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verify(jdbcTemplate).execute("SET LOCAL lock_timeout = '3000ms'");
    }
}
