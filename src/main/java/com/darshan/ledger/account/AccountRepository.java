package com.darshan.ledger.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByCustomerIdOrderByIdAsc(Long customerId);

    // The customer comes along because the transfer rules look at the sender's KYC status.
    @EntityGraph(attributePaths = "customer")
    Optional<Account> findByAccountNumber(AccountNumber accountNumber);

    boolean existsByIdAndCustomerId(Long id, Long customerId);

    boolean existsByAccountNumberAndCustomerId(AccountNumber accountNumber, Long customerId);
}
