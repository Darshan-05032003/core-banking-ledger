package com.darshan.ledger.common.pii;

// Masking rules for the personal data that must never appear in full in a response or a log line.
public final class PiiMasker {

    private static final char MASK = 'X';
    private static final int VISIBLE_SUFFIX = 4;

    private PiiMasker() {
    }

    public static String maskAccountNumber(String accountNumber) {
        return keepLast(accountNumber, VISIBLE_SUFFIX);
    }

    public static String maskPhone(String phone) {
        return keepLast(phone, VISIBLE_SUFFIX);
    }

    // d***@example.com: the domain stays readable for support, the local part does not.
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String keepLast(String value, int visible) {
        if (value == null) {
            return null;
        }
        if (value.length() <= visible) {
            return String.valueOf(MASK).repeat(value.length());
        }
        int hidden = value.length() - visible;
        return String.valueOf(MASK).repeat(hidden) + value.substring(hidden);
    }
}
