package com.darshan.ledger.ledger;

import javax.sql.DataSource;
import org.springframework.jdbc.support.incrementer.PostgresSequenceMaxValueIncrementer;
import org.springframework.stereotype.Component;

// The reference is what support staff and statements quote; it comes from a sequence so it is
// unique across concurrent postings and allocated before the row exists.
@Component
public class JournalReferenceGenerator {

    private static final String PREFIX = "JRN-";

    private final PostgresSequenceMaxValueIncrementer sequence;

    public JournalReferenceGenerator(DataSource dataSource) {
        this.sequence = new PostgresSequenceMaxValueIncrementer(dataSource, "journal_reference_seq");
    }

    public String next() {
        return PREFIX + String.format("%010d", sequence.nextLongValue());
    }
}
