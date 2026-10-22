package com.darshan.ledger.common.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByRequesterAndKeyValue(String requester, String keyValue);

    // A key whose owner died before completing it may be taken over once it is older than the
    // cut-off; the WHERE clause makes sure only one of several waiting requests wins.
    @Modifying
    @Query("""
            update IdempotencyKey k set k.createdAt = :now
            where k.id = :id and k.status = com.darshan.ledger.common.idempotency.IdempotencyStatus.IN_PROGRESS
              and k.createdAt < :cutoff
            """)
    int takeOverStale(Long id, Instant cutoff, Instant now);

    @Modifying
    @Query("""
            delete from IdempotencyKey k
            where k.id = :id and k.status = com.darshan.ledger.common.idempotency.IdempotencyStatus.IN_PROGRESS
            """)
    int deleteInProgress(Long id);
}
