package com.darshan.ledger.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// A simulated message to a customer. No gateway is called; the row is the record of what would
// have been sent, with the recipient already masked so the table never holds a full number.
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private NotificationChannel channel;

    @Column(nullable = false, updatable = false, length = 100)
    private String recipient;

    @Column(nullable = false, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    Notification(UUID eventId, Long accountId, NotificationChannel channel, String recipient, String message,
            Instant createdAt) {
        this.eventId = Objects.requireNonNull(eventId);
        this.accountId = Objects.requireNonNull(accountId);
        this.channel = Objects.requireNonNull(channel);
        this.recipient = Objects.requireNonNull(recipient);
        this.message = Objects.requireNonNull(message);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Notification that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Notification.class.hashCode();
    }
}
