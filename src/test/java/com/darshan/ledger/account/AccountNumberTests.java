package com.darshan.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountNumberTests {

    @Test
    void generatesBranchCodeSequenceAndCheckDigit() {
        AccountNumber number = AccountNumber.of("0001", 1);

        assertThat(number.value()).hasSize(12).startsWith("00010000001");
        assertThat(number.branchCode()).isEqualTo("0001");
        assertThat(Luhn.isValid(number.value())).isTrue();
    }

    @Test
    void generatedNumbersRoundTripThroughTheValidatingConstructor() {
        for (long sequence : new long[] {1, 42, 123_456, AccountNumber.MAX_SEQUENCE}) {
            AccountNumber generated = AccountNumber.of("4821", sequence);

            assertThat(new AccountNumber(generated.value())).isEqualTo(generated);
        }
    }

    @Test
    void rejectsAnInvalidCheckDigit() {
        String valid = AccountNumber.of("0001", 7).value();
        char lastDigit = valid.charAt(11);
        char wrongDigit = lastDigit == '9' ? '0' : (char) (lastDigit + 1);
        String tampered = valid.substring(0, 11) + wrongDigit;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AccountNumber(tampered))
                .withMessage("Account number has an invalid check digit");
    }

    @Test
    void detectsASingleMistypedDigitAnywhereInTheNumber() {
        String valid = AccountNumber.of("1234", 9_876_543).value();

        for (int position = 0; position < valid.length(); position++) {
            char original = valid.charAt(position);
            char changed = original == '9' ? '0' : (char) (original + 1);
            String tampered = valid.substring(0, position) + changed + valid.substring(position + 1);

            assertThatIllegalArgumentException().isThrownBy(() -> new AccountNumber(tampered));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1234", "0001000000", "0001000000123", "00010O000011", "0001 0000011"})
    void rejectsAnythingThatIsNotTwelveDigits(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AccountNumber(value))
                .withMessage("Account number must be 12 digits");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new AccountNumber(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "12345", "A001", ""})
    void rejectsBranchCodesThatAreNotFourDigits(String branchCode) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AccountNumber.of(branchCode, 1))
                .withMessage("Branch code must be 4 digits");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 10_000_000L})
    void rejectsSequencesOutsideTheSevenDigitRange(long sequence) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AccountNumber.of("0001", sequence))
                .withMessageContaining("out of range");
    }

    @Test
    void masksEverythingButTheLastFourDigits() {
        AccountNumber number = AccountNumber.of("0001", 1);
        String lastFour = number.value().substring(8);

        assertThat(number.masked()).isEqualTo("XXXXXXXX" + lastFour);
        assertThat(number).hasToString("XXXXXXXX" + lastFour);
    }

    @Test
    void luhnMatchesTheTextbookExample() {
        assertThat(Luhn.checkDigit("7992739871")).isEqualTo(3);
        assertThat(Luhn.isValid("79927398713")).isTrue();
        assertThat(Luhn.isValid("79927398710")).isFalse();
    }
}
