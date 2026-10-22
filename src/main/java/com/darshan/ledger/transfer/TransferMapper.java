package com.darshan.ledger.transfer;

import com.darshan.ledger.account.AccountNumber;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface TransferMapper {

    @Mapping(target = "fromAccountId", source = "fromAccount.id")
    @Mapping(target = "maskedFromAccountNumber", source = "fromAccount.accountNumber")
    @Mapping(target = "toAccountId", source = "toAccount.id")
    @Mapping(target = "maskedToAccountNumber", source = "toAccount.accountNumber")
    @Mapping(target = "amount", source = "amount.amount")
    @Mapping(target = "currency", source = "amount.currency")
    @Mapping(target = "journalId", source = "journal.id")
    @Mapping(target = "journalReference", source = "journal.reference")
    TransferResponse toResponse(Transfer transfer);

    default String mask(AccountNumber accountNumber) {
        return accountNumber.masked();
    }
}
