package de.buerokratiecopilot.contacts.identifiers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentifierNormalizerTest {

    @Test
    @DisplayName("VAT ids and IBANs lose spaces, dots and dashes and are upper case")
    void vatAndIban() {
        assertThat(IdentifierNormalizer.normalize(IdentifierKind.VAT_ID, "de 123.456-789")).isEqualTo("DE123456789");
        assertThat(IdentifierNormalizer.normalize(IdentifierKind.IBAN, "DE89 3704 0044 0532 0130 00"))
                .isEqualTo("DE89370400440532013000");
    }

    @Test
    @DisplayName("Steuernummern written with slashes or spaces are one number")
    void taxNumber() {
        assertThat(IdentifierNormalizer.normalize(IdentifierKind.TAX_NUMBER, "12/345/67890")).isEqualTo("1234567890".replace("1234567890", "12345" + "67890"));
        assertThat(IdentifierNormalizer.normalize(IdentifierKind.TAX_NUMBER, "12 345 67890"))
                .isEqualTo(IdentifierNormalizer.normalize(IdentifierKind.TAX_NUMBER, "12/345/67890"));
    }

    @Test
    @DisplayName("e-mail is compared in lower case")
    void email() {
        assertThat(IdentifierNormalizer.normalize(IdentifierKind.EMAIL, " Buchhaltung@Muster-GmbH.DE "))
                .isEqualTo("buchhaltung@muster-gmbh.de");
    }

    @Test
    @DisplayName("German phone numbers in their usual spellings become one number")
    void phone() {
        String expected = "+4930123456";
        assertThat(IdentifierNormalizer.normalize(IdentifierKind.PHONE, "+49 30 123456")).isEqualTo(expected);
        assertThat(IdentifierNormalizer.normalize(IdentifierKind.PHONE, "030/123456")).isEqualTo(expected);
        assertThat(IdentifierNormalizer.normalize(IdentifierKind.PHONE, "0049 (0)30 123456")).isEqualTo(expected);
    }

    @Test
    @DisplayName("an IBAN is valid only when its check digits are right")
    void ibanCheckDigits() {
        assertThat(IdentifierNormalizer.isValidIban("DE89370400440532013000")).isTrue();
        assertThat(IdentifierNormalizer.isValidIban("DE89370400440532013001")).isFalse();
        assertThat(IdentifierNormalizer.isValidIban("DE12")).isFalse();
    }

    @Test
    @DisplayName("a German VAT id has nine digits; another country is accepted by shape")
    void vatShape() {
        assertThat(IdentifierNormalizer.looksLikeVatId("DE123456789")).isTrue();
        assertThat(IdentifierNormalizer.looksLikeVatId("DE12345678")).isFalse();
        assertThat(IdentifierNormalizer.looksLikeVatId("ATU12345678")).isTrue();
    }
}
