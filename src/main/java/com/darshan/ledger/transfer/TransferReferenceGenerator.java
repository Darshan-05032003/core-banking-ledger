package com.darshan.ledger.transfer;

import javax.sql.DataSource;
import org.springframework.jdbc.support.incrementer.PostgresSequenceMaxValueIncrementer;
import org.springframework.stereotype.Component;

// Allocated from a sequence, so concurrent transfers never share a reference and a rolled-back
// attempt simply leaves a gap.
@Component
public class TransferReferenceGenerator {

    private static final String PREFIX = "TRF-";

    private final PostgresSequenceMaxValueIncrementer sequence;

    public TransferReferenceGenerator(DataSource dataSource) {
        this.sequence = new PostgresSequenceMaxValueIncrementer(dataSource, "transfer_reference_seq");
    }

    public String next() {
        return PREFIX + String.format("%010d", sequence.nextLongValue());
    }
}
