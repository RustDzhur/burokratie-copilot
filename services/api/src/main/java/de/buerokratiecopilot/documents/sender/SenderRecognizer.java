package de.buerokratiecopilot.documents.sender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.buerokratiecopilot.contacts.identifiers.IdentifierExtractor;
import de.buerokratiecopilot.contacts.identifiers.IdentifierKind;

/**
 * Reads who sent a German letter from its text: the name at the top (letterhead or the small return-address line above the address
 * window), the address, telephone, e-mail address, website, VAT id and IBAN.
 *
 * <p>It only reports what the text says. When it cannot tell who the sender is it says so (empty) instead of guessing: a wrong
 * sender is worse than none, because it is then trusted. The sender is looked for at the top of the letter, because the lines further
 * down name the recipient and other people.</p>
 */
public final class SenderRecognizer {

    public enum Kind { ORGANIZATION, AUTHORITY, PERSON }

    public record Sender(String name, Kind kind, String street, String postalCode, String city, String phone, String email,
                         String website, String vatId, String iban) {

        public String addressLine() {
            StringBuilder line = new StringBuilder();
            if (street != null) {
                line.append(street);
            }
            if (postalCode != null || city != null) {
                if (!line.isEmpty()) {
                    line.append(", ");
                }
                line.append(postalCode == null ? "" : postalCode + " ").append(city == null ? "" : city);
            }
            return line.toString().strip();
        }
    }

    private static final int HEAD_LINES = 12;

    private static final Pattern LEGAL_FORM = Pattern.compile(
            "\\b(GmbH(?:\\s*&\\s*Co\\.?\\s*KG)?|mbH|AG|SE|UG(?:\\s*\\(haftungsbeschränkt\\))?|KGaA|KG|OHG|GbR|PartG(?:mbB)?|e\\.\\s?K\\.|e\\.\\s?V\\.|eG|Stiftung|Ltd\\.?|Inc\\.?)(?=\\s|$|[.,;])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHORITY = Pattern.compile(
            "(?i)\\b(Finanzamt|Jobcenter|Agentur\\s+für\\s+Arbeit|Familienkasse|Landratsamt|Stadtverwaltung|Gemeindeverwaltung|Bürgeramt|"
                    + "Ordnungsamt|Ausländerbehörde|Standesamt|Wohngeldstelle|Gewerbeamt|Amtsgericht|Landgericht|Sozialgericht|Verwaltungsgericht|"
                    + "Staatsanwaltschaft|Polizei|Zoll|Rentenversicherung|Krankenkasse|Berufsgenossenschaft|Industrie-?\\s*und\\s*Handelskammer|IHK|"
                    + "Handwerkskammer|Beitragsservice|Kfz-Zulassungsstelle|Schulamt|Bundesamt|Landesamt|Stadt\\s+\\p{L}+|Gemeinde\\s+\\p{L}+|Amt\\s+für)\\b");
    private static final Pattern ORGANIZATION_WORD = Pattern.compile(
            "(?i)\\b(Stadtwerke|Versicherung(?:s)?|Hausverwaltung|Verwaltung|Bank|Sparkasse|Kanzlei|Praxis|Klinik|Krankenhaus|Schule|Kita|Verlag|"
                    + "Werke|Handel|Systems|Software|Telekom|Energie|Immobilien|Steuerberat\\p{L}*|Rechtsanwält\\p{L}*|Inkasso)\\b");
    private static final Pattern TITLE_WORD = Pattern.compile(
            "(?i)^(rechnung|mahnung|zahlungserinnerung|bescheid|vertrag|kündigung|angebot|auftragsbestätigung|widerspruch|antrag|brief|"
                    + "information|mitteilung|bestätigung|erinnerung|kontoauszug|lieferschein|arztbrief|attest|befund|einspruch|anhörung|"
                    + "\\d+\\.\\s*mahnung|mieterhöhung\\p{L}*|elterninformation|beitragsbescheid|einkommensteuerbescheid)\\b");
    private static final Pattern RECIPIENT_CUE = Pattern.compile("(?i)^(an\\s|herrn?\\s|frau\\s|familie\\s|sehr\\s+geehrte|betreff|bitte\\s|dear\\s)");

    private static final String STREET_WORDS = "stra(?:ß|ss)e|str\\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee|steig|pfad|markt|hof|zeile";
    private static final Pattern STREET = Pattern.compile(
            "(?i)\\b((?:[\\p{L}.\\-]+\\s)*[\\p{L}.\\-]*(?:" + STREET_WORDS + ")|(?:Am|An\\sder|An\\sden|Auf\\sder|Im|In\\sder|Zum|Zur)\\s[\\p{L}.\\-]+)"
                    + "\\s*(\\d{1,4}\\s?[a-zA-Z]?(?:\\s?[-/]\\s?\\d{1,4})?)\\b");
    private static final Pattern POSTAL_CITY = Pattern.compile("\\b(\\d{5})\\s+([A-ZÄÖÜ][\\p{L}.\\-]+(?:[ \\-][\\p{L}.\\-]+){0,3})");
    private static final Pattern RETURN_LINE = Pattern.compile(
            "^(.{3,80}?)\\s*[·•|\\u2013\\u2014]\\s*(.+?\\d+\\s?[a-zA-Z]?)\\s*[·•|,\\u2013\\u2014]\\s*(\\d{5})\\s+([\\p{L}][\\p{L} .\\-]{1,40})$");
    private static final Pattern PHONE = Pattern.compile(
            "(?i)\\b(tel(?:efon)?|fon|phone|mobil|hotline|zentrale)\\.?\\s*[:.]?\\s*(\\+?\\d[\\d\\s/().\\-]{5,22}\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern WEBSITE = Pattern.compile("(?i)\\b((?:https?://)?www\\.[a-z0-9.\\-]+\\.[a-z]{2,}(?:/[^\\s,;]*)?)");

    private SenderRecognizer() {
    }

    public static Optional<Sender> recognise(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip().replaceAll("\\s{2,}", " ");
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        List<String> head = lines.subList(0, Math.min(HEAD_LINES, lines.size()));

        String name = null;
        String street = null;
        String postalCode = null;
        String city = null;
        int nameLine = -1;

        // The small return-address line above the address window says it all in one row.
        for (int i = 0; i < head.size(); i++) {
            Matcher m = RETURN_LINE.matcher(head.get(i));
            if (m.matches() && !TITLE_WORD.matcher(m.group(1)).find()) {
                name = clean(m.group(1));
                street = m.group(2).strip();
                postalCode = m.group(3);
                city = m.group(4).strip();
                nameLine = i;
                break;
            }
        }
        // Otherwise the letterhead: the first line of the top that names an organisation or an authority.
        if (name == null) {
            for (int i = 0; i < Math.min(8, head.size()); i++) {
                String line = head.get(i);
                if (line.length() > 90 || TITLE_WORD.matcher(line).find() || RECIPIENT_CUE.matcher(line).find()) {
                    continue;
                }
                if (LEGAL_FORM.matcher(line).find() || AUTHORITY.matcher(line).find() || ORGANIZATION_WORD.matcher(line).find()) {
                    name = clean(line);
                    nameLine = i;
                    break;
                }
            }
        }
        // A private person writing: a short first line of two or three capitalised words followed by an address.
        if (name == null && !head.isEmpty()) {
            String first = head.get(0);
            if (first.length() <= 60 && first.matches("\\p{Lu}[\\p{L}.\\-]+(?:\\s\\p{Lu}[\\p{L}.\\-]+){1,2}") && !TITLE_WORD.matcher(first).find()
                    && addressWithin(head, 0) != null) {
                String[] address = addressWithin(head, 0);
                return Optional.of(complete(first, Kind.PERSON, address[0], address[1], address[2], lines));
            }
            return Optional.empty();
        }
        if (name == null) {
            return Optional.empty();
        }
        if (street == null) {
            String[] address = addressWithin(head, nameLine);
            if (address != null) {
                street = address[0];
                postalCode = address[1];
                city = address[2];
            }
        }
        Kind kind = AUTHORITY.matcher(name).find() ? Kind.AUTHORITY : Kind.ORGANIZATION;
        return Optional.of(complete(name, kind, street, postalCode, city, lines));
    }

    /** The street, postal code and city in the few lines after the name: on one line or on two. */
    private static String[] addressWithin(List<String> head, int nameLine) {
        for (int i = nameLine + 1; i <= Math.min(nameLine + 4, head.size() - 1); i++) {
            String line = head.get(i);
            Matcher street = STREET.matcher(line);
            Matcher place = POSTAL_CITY.matcher(line);
            if (street.find() && place.find()) {
                return new String[] {(street.group(1) + " " + street.group(2)).strip(), place.group(1), place.group(2).strip()};
            }
            if (street.find(0) && i + 1 < head.size()) {
                Matcher next = POSTAL_CITY.matcher(head.get(i + 1));
                if (next.find()) {
                    return new String[] {(street.group(1) + " " + street.group(2)).strip(), next.group(1), next.group(2).strip()};
                }
            }
            if (place.find(0) && !street.find(0)) {
                return new String[] {null, place.group(1), place.group(2).strip()};
            }
        }
        return null;
    }

    private static Sender complete(String name, Kind kind, String street, String postalCode, String city, List<String> lines) {
        String text = String.join("\n", lines);
        String phone = null;
        Matcher phoneMatcher = PHONE.matcher(text);
        while (phoneMatcher.find()) {
            // A fax line is not the telephone.
            int from = Math.max(0, phoneMatcher.start() - 5);
            String before = text.substring(from, phoneMatcher.start()).toLowerCase(Locale.ROOT);
            if (!before.contains("fax")) {
                phone = phoneMatcher.group(2).strip().replaceAll("\\s{2,}", " ");
                break;
            }
        }
        String email = null;
        Matcher emailMatcher = EMAIL.matcher(text);
        while (emailMatcher.find()) {
            if (!emailMatcher.group().toLowerCase(Locale.ROOT).startsWith("noreply") && !emailMatcher.group().toLowerCase(Locale.ROOT).startsWith("no-reply")) {
                email = emailMatcher.group();
                break;
            }
        }
        Matcher web = WEBSITE.matcher(text);
        String website = web.find() ? web.group(1) : null;
        String vat = null;
        String iban = null;
        for (var found : IdentifierExtractor.extract(text)) {
            if (found.kind() == IdentifierKind.VAT_ID && vat == null) {
                vat = found.normalized();
            }
            if (found.kind() == IdentifierKind.IBAN && iban == null) {
                iban = found.normalized();
            }
        }
        return new Sender(name, kind, street, postalCode, city, phone, email, website, vat, iban);
    }

    private static String clean(String raw) {
        return raw.strip().replaceAll("^[\\-–—·•|:,.\\s]+|[\\-–—·•|:,.\\s]+$", "");
    }
}
