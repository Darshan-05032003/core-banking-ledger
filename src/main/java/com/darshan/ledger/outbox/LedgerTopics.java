package com.darshan.ledger.outbox;

public final class LedgerTopics {

    public static final String TRANSACTIONS = "ledger.transactions.v1";
    public static final String TRANSACTIONS_DLT = TRANSACTIONS + ".DLT";

    public static final String EVENT_ID_HEADER = "eventId";
    public static final String EVENT_TYPE_HEADER = "eventType";

    private LedgerTopics() {
    }
}
