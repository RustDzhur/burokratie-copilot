package de.buerokratiecopilot.documents.classification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextSanityTest {

    @Test
    @DisplayName("German prose is readable, noise from an upside-down photo is not, empty text is not")
    void judgesReadability() {
        String prose = "Sehr geehrte Damen und Herren, wir bitten Sie, die Rechnung bis zum 20.10.2026 zu bezahlen. "
                + "Der Betrag ist nicht verhandelbar und wird von uns mit dem Konto abgeglichen. Bei Fragen rufen Sie uns bitte an, "
                + "wir sind für Sie da und helfen Ihnen gerne weiter.";
        String noise = "ee v Sn en Arie 4s) pun yaqsua HERE apınm u uljuaysissy PpeW plemaunsg yonsaq nyrs wap yw Bueyuawwesnz Wi "
                + "WaypuNnsed ınz uadeıg als us abipupysnz sop sasıamyaou dwı uaj36aja6s0n sap ywayx6nYyriy Uayrijyjoyu Japo";

        assertThat(TextSanity.looksUnreadable(prose)).isFalse();
        assertThat(TextSanity.looksUnreadable(noise)).isTrue();
        assertThat(TextSanity.looksUnreadable(null)).isTrue();
        assertThat(TextSanity.looksUnreadable("   ")).isTrue();
        assertThat(TextSanity.looksUnreadable("Rechnung 119,00 EUR")).as("too short to judge").isFalse();
    }
}
