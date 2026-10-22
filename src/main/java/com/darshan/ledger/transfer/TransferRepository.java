package com.darshan.ledger.transfer;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // Everything that has left, or is reserved to leave, the account in the window. Failed,
    // rejected and expired attempts never moved money, so they do not count against the limit.
    @Query("""
            select sum(t.amount.amount) from Transfer t
            where t.fromAccount.id = :accountId
              and t.status in (com.darshan.ledger.transfer.TransferStatus.COMPLETED,
                               com.darshan.ledger.transfer.TransferStatus.PENDING_APPROVAL)
              and t.createdAt >= :from and t.createdAt < :to
            """)
    Optional<BigDecimal> sumOutgoingBetween(Long accountId, Instant from, Instant to);

    // SELECT ... FOR UPDATE on the transfer row: approve, reject and expiry all take it first, so
    // whichever decision arrives second waits and then finds the transfer no longer pending.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :transferId")
    Optional<Transfer> lockById(Long transferId);

    Page<Transfer> findByStatusOrderByCreatedAtAscIdAsc(TransferStatus status, Pageable pageable);

    boolean existsByIdAndFromAccountCustomerId(Long id, Long customerId);
}
