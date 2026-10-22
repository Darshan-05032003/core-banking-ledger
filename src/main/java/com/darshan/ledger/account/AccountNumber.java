package com.darshan.ledger.account;

import com.darshan.ledger.common.pii.PiiMasker;
import java.util.Objects;
import java.util.regex.Pattern;

// Twelve digits: a four-digit branch code, a seven-digit running sequence and a Luhn check digit.
// The check digit catches the typing mistakes that matter most when a customer keys in a
// beneficiary account: any single wrong digit and almost every swap of two neighbouring digits.
public record AccountNumber(String value) {

    public static final int LENGTH = 12;
    public static final int BRANCH_CODE_LENGTH = 4;
    public static final int SEQUENCE_LENGTH = 7;
    static final long MAX_SEQUENCE = 9_999_999L;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{" + LENGTH + "}");
    private static final Pattern BRANCH_CODE = Pattern.compile("[0-9]{" + BRANCH_CODE_LENGTH + "}");

    public AccountNumber {
        Objects.requireNonNull(value, "account number must not be null");
        if (!DIGITS.matcher(value).matches()) {
            throw new IllegalArgumentException("Account number must be " + LENGTH + " digits");
        }
        if (!Luhn.isValid(value)) {
            throw new IllegalArgumentException("Account number has an invalid check digit");
        }
    }

    public static AccountNumber of(String branchCode, long sequence) {
        Objects.requireNonNull(branchCode, "branch code must not be null");
        if (!BRANCH_CODE.matcher(branchCode).matches()) {
            throw new IllegalArgumentException("Branch code must be " + BRANCH_CODE_LENGTH + " digits");
        }
        if (sequence < 1 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Account sequence " + sequence + " is out of range");
        }
        String payload = branchCode + String.format("%0" + SEQUENCE_LENGTH + "d", sequence);
        return new AccountNumber(payload + Luhn.checkDigit(payload));
    }

    public String branchCode() {
        return value.substring(0, BRANCH_CODE_LENGTH);
    }

    public String masked() {
        return PiiMasker.maskAccountNumber(value);
    }

    // The masked form is what ends up in logs and error messages by default.
    @Override
    public String toString() {
        return masked();
    }
}
