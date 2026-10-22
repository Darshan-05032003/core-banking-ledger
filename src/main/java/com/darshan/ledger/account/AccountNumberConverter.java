package com.darshan.ledger.account;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// Stores the twelve digits as a plain string column; loading re-runs the format and Luhn checks.
@Converter(autoApply = true)
public class AccountNumberConverter implements AttributeConverter<AccountNumber, String> {

    @Override
    public String convertToDatabaseColumn(AccountNumber attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public AccountNumber convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new AccountNumber(dbData);
    }
}
