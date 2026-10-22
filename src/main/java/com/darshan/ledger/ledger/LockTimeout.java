package com.darshan.ledger.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Bounds how long a posting waits for a balance row another transaction holds. SET LOCAL lasts
// until the end of the current transaction, so it is issued inside the transaction right before
// the first row lock; outside a transaction PostgreSQL would only log a warning and ignore it.
@Component
public class LockTimeout {

    private final JdbcTemplate jdbcTemplate;
    private final LedgerProperties properties;

    public LockTimeout(JdbcTemplate jdbcTemplate, LedgerProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void applyToCurrentTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A lock timeout can only be set inside a transaction");
        }
        jdbcTemplate.execute("SET LOCAL lock_timeout = '" + properties.lockTimeout().toMillis() + "ms'");
    }
}
