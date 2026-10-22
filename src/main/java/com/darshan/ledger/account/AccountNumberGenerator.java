package com.darshan.ledger.account;

import javax.sql.DataSource;
import org.springframework.jdbc.support.incrementer.PostgresSequenceMaxValueIncrementer;
import org.springframework.stereotype.Component;

// The sequence hands out numbers outside the caller's transaction, so two tellers opening accounts
// at the same time never receive the same number and a rolled-back opening simply leaves a gap.
@Component
public class AccountNumberGenerator {

    private final PostgresSequenceMaxValueIncrementer sequence;
    private final AccountProperties properties;

    public AccountNumberGenerator(DataSource dataSource, AccountProperties properties) {
        this.sequence = new PostgresSequenceMaxValueIncrementer(dataSource, "account_number_seq");
        this.properties = properties;
    }

    public AccountNumber next() {
        return AccountNumber.of(properties.branchCode(), sequence.nextLongValue());
    }
}
