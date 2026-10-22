package com.darshan.ledger.ledger;

import com.darshan.ledger.account.Account;
import com.darshan.ledger.common.money.Money;
import java.util.Objects;

public record PostingLeg(AccountRef account, EntryDirection direction, Money amount) {

    public PostingLeg {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A posting leg must carry a positive amount, got " + amount);
        }
    }

    public static PostingLeg debit(Account account, Money amount) {
        return new PostingLeg(new AccountRef.CustomerAccountRef(account), EntryDirection.DEBIT, amount);
    }

    public static PostingLeg credit(Account account, Money amount) {
        return new PostingLeg(new AccountRef.CustomerAccountRef(account), EntryDirection.CREDIT, amount);
    }

    public static PostingLeg debit(GlAccount glAccount, Money amount) {
        return new PostingLeg(new AccountRef.GlAccountRef(glAccount), EntryDirection.DEBIT, amount);
    }

    public static PostingLeg credit(GlAccount glAccount, Money amount) {
        return new PostingLeg(new AccountRef.GlAccountRef(glAccount), EntryDirection.CREDIT, amount);
    }

    public PostingLeg opposite() {
        return new PostingLeg(account, direction.opposite(), amount);
    }

    public boolean isDebit() {
        return direction == EntryDirection.DEBIT;
    }
}
