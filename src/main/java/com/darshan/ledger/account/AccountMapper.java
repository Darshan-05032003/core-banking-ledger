package com.darshan.ledger.account;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface AccountMapper {

    @Mapping(target = "maskedAccountNumber", source = "accountNumber")
    @Mapping(target = "customerId", source = "customer.id")
    AccountResponse toResponse(Account account);

    List<AccountResponse> toResponses(List<Account> accounts);

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "maskedAccountNumber", source = "account.accountNumber")
    @Mapping(target = "ledgerBalance", source = "snapshot.ledgerBalance.amount")
    @Mapping(target = "availableBalance", source = "snapshot.availableBalance.amount")
    @Mapping(target = "currency", source = "account.currency")
    AccountBalanceResponse toBalanceResponse(Account account, AccountBalanceSnapshot snapshot);

    default String mask(AccountNumber accountNumber) {
        return accountNumber.masked();
    }
}
