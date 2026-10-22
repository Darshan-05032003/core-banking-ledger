package com.darshan.ledger.outbox;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.ledger.AccountRef;
import com.darshan.ledger.ledger.JournalEntry;
import com.darshan.ledger.ledger.JournalPosted;
import com.darshan.ledger.ledger.LedgerEntry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// Writes the outbox rows of a posted journal: one per customer account the journal touched, keyed
// by that account so everything about one account lands on one partition in posting order. GL
// legs get no event of their own; they appear as legs inside the customer events. The listener
// runs synchronously, on the posting thread, and insists on the posting transaction (MANDATORY):
// an outbox row that could be committed without its journal, or the other way round, would be
// the dual-write problem this table exists to avoid.
@Component
public class JournalPostedOutboxWriter {

    static final String AGGREGATE_TYPE = "JOURNAL";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JournalPostedOutboxWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper,
            Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(JournalPosted posted) {
        JournalEntry journal = posted.journal();
        List<LedgerTransactionPostedV1.Leg> legs = journal.getEntries().stream().map(this::toLeg).toList();
        for (LedgerEntry entry : journal.getEntries()) {
            if (entry.accountRef() instanceof AccountRef.CustomerAccountRef(Account account)) {
                UUID eventId = UUID.randomUUID();
                LedgerTransactionPostedV1 event = new LedgerTransactionPostedV1(
                        LedgerTransactionPostedV1.SCHEMA_VERSION, eventId, journal.getId(), journal.getReference(),
                        journal.getType(), journal.getPostedAt(), journal.getReversalOfId(), account.getId(),
                        account.getAccountNumber().masked(), entry.getDirection(), entry.getAmount().amount(),
                        entry.getAmount().currency().getCurrencyCode(), legs);
                outboxEventRepository.insert(eventId, AGGREGATE_TYPE, journal.getId().toString(),
                        LedgerTransactionPostedV1.EVENT_TYPE, LedgerTransactionPostedV1.SCHEMA_VERSION,
                        account.getId().toString(), objectMapper.writeValueAsString(event), clock.instant());
            }
        }
    }

    private LedgerTransactionPostedV1.Leg toLeg(LedgerEntry entry) {
        return switch (entry.accountRef()) {
            case AccountRef.CustomerAccountRef(Account account) -> new LedgerTransactionPostedV1.Leg(account.getId(),
                    account.getAccountNumber().masked(), null, entry.getDirection(), entry.getAmount().amount());
            case AccountRef.GlAccountRef gl -> new LedgerTransactionPostedV1.Leg(null, null,
                    gl.glAccount().getCode().name(), entry.getDirection(), entry.getAmount().amount());
        };
    }
}
