package com.darshan.ledger.ledger;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {

    // SELECT ... FOR UPDATE: the row stays locked until the posting transaction ends, so two
    // postings against the same account are applied one after the other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AccountBalance b where b.accountId = :accountId")
    Optional<AccountBalance> lockByAccountId(Long accountId);
}
