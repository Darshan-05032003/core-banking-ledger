package com.darshan.ledger.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByAccountIdOrderByIdAsc(Long accountId);

    Optional<Notification> findByEventId(UUID eventId);
}
