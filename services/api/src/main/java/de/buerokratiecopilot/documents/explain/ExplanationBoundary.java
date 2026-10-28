package de.buerokratiecopilot.documents.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What an explanation of a document may not say (TR-04).
 *
 * <p>An explanation helps a person understand a letter. It does not decide whether a claim is lawful, does not tell the
 * person to ignore a letter, and does not speak as a lawyer, a tax advisor or an authority. A claim that does is not shown;
 * the reader is given a fixed sentence that sends the question to a qualified person instead. The check is deliberately
 * blunt: a phrase that sounds like a legal conclusion is held back, even if it might have been harmless.</p>
 */
public final class ExplanationBoundary {

    public static final String LEGAL_CONCLUSION = "LEGAL_CONCLUSION";
    public static final String SPEAKS_AS_PROFESSIONAL = "SPEAKS_AS_PROFESSIONAL";
    public static final String ADVISES_TO_IGNORE = "ADVISES_TO_IGNORE";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern LEGAL = Pattern.compile(
            "rechtswidrig|unrechtmäßig|unwirksam|nichtig|verjährt|rechtlich verpflichtet|sind verpflichtet|müssen sie zahlen"
                    + "|sie schulden|anspruch auf|keinen anspruch|erfolgsaussicht|prozess gewinnen|rechtlich (zulässig|unzulässig)"
                    + "|legally (binding|obliged|required)|you (legally )?(owe|must pay)|unlawful|illegal|null and void|invalid contract"
                    + "|you will (win|lose)|statute of limitations (has )?(expired|passed)"
                    + "|незаконн|недействительн|вы обязаны|вы должны (заплатить|оплатить)|вы (выиграете|проиграете)"
                    + "|срок давности (истёк|истек)|иск не имеет", FLAGS);

    private static final Pattern PROFESSIONAL = Pattern.compile(
            "als (ihr|dein) (anwalt|rechtsanwalt|steuerberater|jurist)|ich bin (ein |ihr )?(anwalt|rechtsanwalt|steuerberater|jurist)"
                    + "|im namen (des|der) (gericht|behörde|finanzamt)|wir als behörde|rechtsberatung erteilen"
                    + "|as your (lawyer|attorney|tax advisor)|i am (a |your )?(lawyer|attorney|tax advisor)|on behalf of the (court|authority)"
                    + "|как (ваш )?(адвокат|юрист|налоговый консультант)|я (ваш )?(адвокат|юрист)|от имени (суда|ведомства|органа)", FLAGS);

    private static final Pattern IGNORE = Pattern.compile(
            "ignorier|brauchen? (sie )?nicht (zu )?(reagieren|antworten)|nicht reagieren|können sie (ruhig )?(übergehen|ignorieren)"
                    + "|you can (safely )?ignore|no need to (respond|reply)|do not (respond|reply)"
                    + "|можно (спокойно )?игнорировать|не нужно (отвечать|реагировать)|не отвечайте", FLAGS);

    private ExplanationBoundary() {
    }

    /** The codes of the rules a sentence breaks; empty when it stays within what an explanation may say. */
    public static List<String> violations(String text) {
        List<String> codes = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return codes;
        }
        if (LEGAL.matcher(text).find()) {
            codes.add(LEGAL_CONCLUSION);
        }
        if (PROFESSIONAL.matcher(text).find()) {
            codes.add(SPEAKS_AS_PROFESSIONAL);
        }
        if (IGNORE.matcher(text).find()) {
            codes.add(ADVISES_TO_IGNORE);
        }
        return codes;
    }
}
