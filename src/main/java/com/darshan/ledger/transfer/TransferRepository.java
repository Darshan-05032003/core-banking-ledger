package com.darshan.ledger.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // Everything that has left, or is reserved to leave, the account in the window. Failed
    // attempts never moved money, so they do not count against the limit.
    @Query("""
            select sum(t.amount.amount) from Transfer t
            where t.fromAccount.id = :accountId
              and t.status <> com.darshan.ledger.transfer.TransferStatus.FAILED
              and t.createdAt >= :from and t.createdAt < :to
            """)
    Optional<BigDecimal> sumOutgoingBetween(Long accountId, Instant from, Instant to);
}
