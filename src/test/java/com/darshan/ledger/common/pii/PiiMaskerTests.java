package com.darshan.ledger.common.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiMaskerTests {

    @Test
    void masksAccountNumbersToTheLastFourDigits() {
        assertThat(PiiMasker.maskAccountNumber("000100000014")).isEqualTo("XXXXXXXX0014");
    }

    @Test
    void masksPhoneNumbersToTheLastFourDigits() {
        assertThat(PiiMasker.maskPhone("+919876543210")).isEqualTo("XXXXXXXXX3210");
        assertThat(PiiMasker.maskPhone("9876543210")).isEqualTo("XXXXXX3210");
    }

    @Test
    void shortValuesAreMaskedCompletely() {
        assertThat(PiiMasker.maskPhone("4821")).isEqualTo("XXXX");
        assertThat(PiiMasker.maskPhone("12")).isEqualTo("XX");
    }

    @Test
    void masksTheLocalPartOfEmails() {
        assertThat(PiiMasker.maskEmail("darshan@example.com")).isEqualTo("d***@example.com");
        assertThat(PiiMasker.maskEmail("a@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void emailsWithoutAUsableLocalPartAreMaskedCompletely() {
        assertThat(PiiMasker.maskEmail("not-an-email")).isEqualTo("***");
        assertThat(PiiMasker.maskEmail("@example.com")).isEqualTo("***");
    }

    @Test
    void nullStaysNull() {
        assertThat(PiiMasker.maskEmail(null)).isNull();
        assertThat(PiiMasker.maskPhone(null)).isNull();
        assertThat(PiiMasker.maskAccountNumber(null)).isNull();
    }
}
