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

    default String mask(AccountNumber accountNumber) {
        return accountNumber.masked();
    }
}
