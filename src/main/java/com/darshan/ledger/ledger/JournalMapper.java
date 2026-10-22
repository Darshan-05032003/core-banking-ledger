package com.darshan.ledger.ledger;

import com.darshan.ledger.account.AccountNumber;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface JournalMapper {

    JournalResponse toResponse(JournalEntry journal);

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "maskedAccountNumber", source = "account.accountNumber")
    @Mapping(target = "glAccount", source = "glAccount.code")
    @Mapping(target = "amount", source = "amount.amount")
    @Mapping(target = "currency", source = "amount.currency")
    LedgerEntryResponse toEntryResponse(LedgerEntry entry);

    // GL legs have no customer account, so the number arrives as null.
    default String mask(AccountNumber accountNumber) {
        return accountNumber == null ? null : accountNumber.masked();
    }
}
