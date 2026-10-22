package com.darshan.ledger.ledger;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface GlAccountRepository extends Repository<GlAccount, Long> {

    Optional<GlAccount> findByCode(GlAccountCode code);

    default GlAccount require(GlAccountCode code) {
        return findByCode(code)
                .orElseThrow(() -> new IllegalStateException("GL account " + code + " has not been seeded"));
    }
}
