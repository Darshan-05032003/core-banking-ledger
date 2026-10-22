package com.darshan.ledger.notification;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.account.AccountRepository;
import com.darshan.ledger.common.pii.PiiMasker;
import com.darshan.ledger.customer.Customer;
import com.darshan.ledger.outbox.LedgerTransactionPostedV1;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Turns a ledger event into one simulated message for the account's customer. Debits go out as
// an SMS, the channel a customer sees fastest when something is wrong; credits as an email. The
// processed_event row and the notification are written in one transaction, so a redelivered
// event (the relay crashed after the broker's acknowledgement, a rebalance replayed an offset)
// finds its id already recorded and writes nothing.
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private final ProcessedEvents processedEvents;
    private final NotificationRepository notificationRepository;
    private final AccountRepository accountRepository;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationService(ProcessedEvents processedEvents, NotificationRepository notificationRepository,
            AccountRepository accountRepository, NotificationProperties properties, Clock clock) {
        this.processedEvents = processedEvents;
        this.notificationRepository = notificationRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Optional<Notification> notify(LedgerTransactionPostedV1 event) {
        if (!processedEvents.markProcessed(event.eventId(), properties.groupId(), clock.instant())) {
            log.info("Event {} was already processed, skipping the duplicate delivery", event.eventId());
            return Optional.empty();
        }
        Account account = accountRepository.findById(event.accountId())
                .orElseThrow(() -> new UnsupportedEventException(
                        "Event " + event.eventId() + " names account " + event.accountId() + ", which does not exist"));
        Customer customer = account.getCustomer();
        Notification notification = switch (event.direction()) {
            case DEBIT -> new Notification(event.eventId(), account.getId(), NotificationChannel.SMS,
                    PiiMasker.maskPhone(customer.getPhone()), message(event, "debited from"), clock.instant());
            case CREDIT -> new Notification(event.eventId(), account.getId(), NotificationChannel.EMAIL,
                    PiiMasker.maskEmail(customer.getEmail()), message(event, "credited to"), clock.instant());
        };
        notificationRepository.save(notification);
        log.info("{} notification recorded for account {} from event {}", notification.getChannel(),
                account.getId(), event.eventId());
        return Optional.of(notification);
    }

    private String message(LedgerTransactionPostedV1 event, String verb) {
        return "%s %s %s account %s on %s UTC (%s %s)".formatted(event.currency(), event.amount().toPlainString(),
                verb, event.maskedAccountNumber(), WHEN.withZone(clock.getZone()).format(event.postedAt()),
                event.journalType(), event.journalReference());
    }
}
