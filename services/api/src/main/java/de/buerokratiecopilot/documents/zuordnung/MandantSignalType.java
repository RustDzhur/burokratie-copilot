package de.buerokratiecopilot.documents.zuordnung;

import de.buerokratiecopilot.contacts.identifiers.IdentifierKind;

/**
 * The signals a voucher can carry about its client (§5.2), each with the weight it carries on its own.
 *
 * <p>The numbers are the specification's: an IBAN or a VAT id is close to proof, an e-mail address is strong, a
 * company name is a hint, a keyword is barely more than a guess. They are written down here instead of being
 * scattered through the matcher so that a disagreement about the weighting is a one-line change and the
 * threshold in {@link de.buerokratiecopilot.documents.zordnung.MandantRouting} stays the only gate.</p>
 */
public enum MandantSignalType {

    /** The address the mail came from, or the e-mail printed on the letter. */
    ABSENDER_EMAIL(0.90),
    /** A bank account written on the voucher: the strongest identifier a German invoice carries. */
    IBAN(0.95),
    /** USt-IdNr. */
    USTID(0.95),
    /** Steuernummer. Slightly below the VAT id because it is often the office's own number. */
    STEUERNUMMER(0.92),
    /** The company name as the sender or as written in the text. */
    FIRMENNAME(0.80),
    /** A learned word or phrase ("Musterlayout", "Kanzlei Schneider"). */
    SCHLUESSELWORT(0.60);

    private final double strength;

    MandantSignalType(double strength) {
        this.strength = strength;
    }

    /** How much this signal alone counts, from 0 to 1. */
    public double strength() {
        return strength;
    }

    /**
     * The routing type of an identifier found in the text, or null when the identifier is not a routing signal.
     *
     * <p>Phone numbers, domains and free external ids are useful for contact matching but say little about whose
     * bookkeeping the voucher belongs to; they are deliberately not promoted to a routing signal.</p>
     */
    public static MandantSignalType forIdentifier(IdentifierKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case EMAIL -> ABSENDER_EMAIL;
            case IBAN -> IBAN;
            case VAT_ID -> USTID;
            case TAX_NUMBER -> STEUERNUMMER;
            case DOMAIN, PHONE, EXTERNAL -> null;
        };
    }
}
