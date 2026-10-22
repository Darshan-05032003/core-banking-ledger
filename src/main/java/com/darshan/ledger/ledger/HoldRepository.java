package com.darshan.ledger.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HoldRepository extends JpaRepository<Hold, Long> {

    Optional<Hold> findByTransferIdAndStatus(Long transferId, HoldStatus status);

    // Oldest expiry first, so a backlog is worked off in the order it became due.
    @Query("""
            select h.transferId from Hold h
            where h.status = com.darshan.ledger.ledger.HoldStatus.ACTIVE and h.expiresAt <= :now
            order by h.expiresAt, h.id
            """)
    List<Long> findTransferIdsOfHoldsExpiredAt(Instant now, Pageable limit);
}
