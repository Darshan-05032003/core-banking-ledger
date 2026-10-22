package com.darshan.ledger.account;

final class Luhn {

    private Luhn() {
    }

    // Starting from the right, every second digit is doubled (and reduced by 9 when the result
    // has two digits); the number is valid when the digit sum is a multiple of ten.
    static boolean isValid(String digits) {
        return weightedSum(digits, false) % 10 == 0;
    }

    // The check digit is whatever makes the complete number pass isValid. Because it will become
    // the rightmost digit, the payload digits are weighted as if that extra digit were present.
    static int checkDigit(String payload) {
        int sum = weightedSum(payload, true);
        return (10 - sum % 10) % 10;
    }

    private static int weightedSum(String digits, boolean doubleRightmost) {
        int sum = 0;
        boolean doubleIt = doubleRightmost;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum;
    }
}
