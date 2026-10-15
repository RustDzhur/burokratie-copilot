package de.buerokratiecopilot.documents.explain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TR-04: an explanation does not draw legal conclusions, does not speak as a lawyer or an authority, and never says "ignore it". */
class ExplanationBoundaryTest {

    @Test
    @DisplayName("legal conclusions are caught in German, English and Russian")
    void legalConclusions() {
        assertThat(ExplanationBoundary.violations("Die Forderung ist rechtswidrig.")).contains(ExplanationBoundary.LEGAL_CONCLUSION);
        assertThat(ExplanationBoundary.violations("Der Anspruch ist verjährt.")).contains(ExplanationBoundary.LEGAL_CONCLUSION);
        assertThat(ExplanationBoundary.violations("Sie sind verpflichtet, den Betrag zu zahlen.")).contains(ExplanationBoundary.LEGAL_CONCLUSION);
        assertThat(ExplanationBoundary.violations("You legally owe this amount.")).contains(ExplanationBoundary.LEGAL_CONCLUSION);
        assertThat(ExplanationBoundary.violations("This demand is unlawful.")).contains(ExplanationBoundary.LEGAL_CONCLUSION);
        assertThat(ExplanationBoundary.violations("Вы обязаны заплатить эту сумму.")).contains(ExplanationBoundary.LEGAL_CONCLUSION);
        assertThat(ExplanationBoundary.violations("Срок давности истёк.")).contains(ExplanationBoundary.LEGAL_CONCLUSION);
    }

    @Test
    @DisplayName("the AI is not presented as a lawyer, a tax advisor or an authority")
    void speaksAsProfessional() {
        assertThat(ExplanationBoundary.violations("Als Ihr Anwalt rate ich zum Widerspruch.")).contains(ExplanationBoundary.SPEAKS_AS_PROFESSIONAL);
        assertThat(ExplanationBoundary.violations("I am your lawyer and I confirm this.")).contains(ExplanationBoundary.SPEAKS_AS_PROFESSIONAL);
        assertThat(ExplanationBoundary.violations("Wir als Behörde entscheiden das.")).contains(ExplanationBoundary.SPEAKS_AS_PROFESSIONAL);
        assertThat(ExplanationBoundary.violations("Как ваш юрист, я считаю иначе.")).contains(ExplanationBoundary.SPEAKS_AS_PROFESSIONAL);
    }

    @Test
    @DisplayName("a letter is never declared ignorable")
    void neverIgnore() {
        assertThat(ExplanationBoundary.violations("Sie können dieses Schreiben ignorieren.")).contains(ExplanationBoundary.ADVISES_TO_IGNORE);
        assertThat(ExplanationBoundary.violations("You can safely ignore this letter.")).contains(ExplanationBoundary.ADVISES_TO_IGNORE);
        assertThat(ExplanationBoundary.violations("Можно спокойно игнорировать это письмо.")).contains(ExplanationBoundary.ADVISES_TO_IGNORE);
    }

    @Test
    @DisplayName("plain description of what the letter says passes")
    void plainStatementsPass() {
        assertThat(ExplanationBoundary.violations("Das Schreiben nennt einen Betrag von 280,00 EUR und eine Frist bis zum 20.10.2026.")).isEmpty();
        assertThat(ExplanationBoundary.violations("The sender asks for a payment of 280.00 EUR.")).isEmpty();
        assertThat(ExplanationBoundary.violations("Отправитель просит оплатить счёт до 20.10.2026.")).isEmpty();
        assertThat(ExplanationBoundary.violations(null)).isEmpty();
    }
}
