package de.buerokratiecopilot.settings;

import java.time.LocalDate;
import java.util.Locale;

import org.springframework.stereotype.Service;

import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * The client's tax facts, as they are needed by an ELSTER export (spec §31; docs/ELSTER_MAPPING.md §6.2).
 *
 * <p>Four values decide whether an exported amount is right, and none of them can be derived from a
 * receipt: whether the client is a Kleinunternehmer (§ 19 UStG), whether VAT is due when the invoice is
 * written or when it is paid, which business year is meant, and whether Bewirtung is split 70/30. They
 * are stored as tenant settings (with platform defaults) and read here with the documented fallbacks, so
 * that an export never silently invents one of them.</p>
 *
 * <p>The Wirtschaftsjahr has no static default on purpose: "current year" is resolved on read. A value
 * seeded in the migration would be the year of the installation forever.</p>
 */
@Service
public class TaxSettingsService {

    public static final String KLEINUNTERNEHMER = "tax.kleinunternehmer";
    public static final String SOLL_IST = "tax.soll_ist";
    public static final String WIRTSCHAFTSJAHR = "tax.wirtschaftsjahr";
    public static final String BEWIRTUNG_SPLIT = "tax.bewirtung_split";

    public static final String SOLL = "SOLL";
    public static final String IST = "IST";

    private final SettingsService settings;

    public TaxSettingsService(SettingsService settings) {
        this.settings = settings;
    }

    /** The values an export is built from. */
    public record TaxSettings(boolean kleinunternehmer, String sollIst, int wirtschaftsjahr,
                              boolean bewirtungSplit) {
    }

    public TaxSettings current() {
        int fallbackYear = LocalDate.now().getYear();
        int year = settings.getInt(WIRTSCHAFTSJAHR, fallbackYear);
        if (year < 1900 || year > 2200) {
            year = fallbackYear;
        }
        return new TaxSettings(
                settings.getBoolean(KLEINUNTERNEHMER, false),
                sollIst(settings.getString(SOLL_IST).orElse(null)),
                year,
                settings.getBoolean(BEWIRTUNG_SPLIT, true));
    }

    /** Only SOLL and IST are meaningful; anything else falls back to SOLL rather than breaking an export. */
    private static String sollIst(String raw) {
        if (raw == null) {
            return SOLL;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return IST.equals(value) ? IST : SOLL;
    }
}
