package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import java.util.Objects;

// A ledger line hits either a customer account or one of the bank's own GL accounts, never both.
public sealed interface AccountRef {

    record CustomerAccountRef(Account account) implements AccountRef {

        public CustomerAccountRef {
            Objects.requireNonNull(account, "account must not be null");
        }
    }

    record GlAccountRef(GlAccount glAccount) implements AccountRef {

        public GlAccountRef {
            Objects.requireNonNull(glAccount, "GL account must not be null");
        }
    }
}
