package de.buerokratiecopilot.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import de.buerokratiecopilot.templates.TemplateRenderer.Field;

/**
 * The values a contract template draws from the contract, its parties, the workspace and the customer
 * contact (spec §13, V72).
 *
 * <p><strong>This class is the contract field catalogue.</strong> A template may address four sources,
 * and each source answers only the paths listed here. Everything else resolves to nothing, which the
 * renderer then treats as a missing value — so a template that asks {@code {{Vertrag.Horoskop}}} is
 * blocked with a clear message instead of producing a document with a hole in it.</p>
 *
 * <p>The paths are deliberately German, matching the German sources: a document reads
 * {@code {{Vertrag.Laufzeit}}} rather than {@code {{Contract.term}}}. Paths are matched case-insensitively,
 * so {@code Vertrag.title} and {@code VERTRAG.Title} address the same value.</p>
 *
 * <pre>
 * Vertrag.Title                 the title of the contract
 * Vertrag.Nummer                contract_number
 * Vertrag.Typ                   contract_type as stored (RENT, SERVICE, ...)
 * Vertrag.Status                lifecycle status as stored (DRAFT, ACTIVE, ...)
 * Vertrag.Beschreibung          description
 * Vertrag.Start                 start_date, formatted 31.12.2026
 * Vertrag.Ende                  end_date
 * Vertrag.UnterzeichnetAm       signed_date
 * Vertrag.AutoVerlaengerung     "Ja" or "Nein"
 * Vertrag.VerlaengerungsMonate  renewal_period_months
 * Vertrag.Kuendigungsfrist      notice period as words: "3 Monate"
 * Vertrag.KuendigungBis         notice_deadline_date
 * Vertrag.Betrag                cost_amount with a German decimal comma
 * Vertrag.Waehrung              cost_currency
 * Vertrag.Zahlungsintervall     cost_interval (MONTHLY, ...)
 * Vertrag.Laufzeit              "01.01.2026 – 31.12.2026" (or only the given end)
 *
 * Partei.Name                   the primary party's name (the first party when none is primary)
 * Partei.Rolle                  its role as stored
 * Partei.Anschrift              its address, from the linked contact or organisation
 * Partei.Email / Partei.Telefon from its linked contact
 * Partei.Gegenpartei.Name       the first COUNTERPARTY party's name; the same for Rolle, Anschrift,
 *                               Email, Telefon. Other roles: OwnSide, Buerge, Agent, Zeuge, Other.
 *
 * Werk.Name                     the workspace's name
 * Werk.Adresse                  the address of the linked organisation
 *
 * Kunde.Name                    the customer contact's display name
 * Kunde.Organisation            its organisation name
 * Kunde.Anschrift               its address
 * Kunde.Email / Kunde.Telefon   its contact data
 * </pre>
 *
 * <p>The class is pure: it reads records and returns a map, so it can be tested without a database and
 * the vocabulary above cannot drift silently.</p>
 */
public final class ContractTemplateValues {

    /** What a contract contributes. */
    public record ContractData(String title, String contractNumber, String contractType, String status,
                               String description, LocalDate startDate, LocalDate endDate,
                               LocalDate signedDate, Boolean autoRenewal, Integer renewalPeriodMonths,
                               Integer noticePeriodValue, String noticePeriodUnit,
                               LocalDate noticeDeadlineDate, BigDecimal costAmount, String costCurrency,
                               String costInterval) {
    }

    /** One party of the contract, with the contact or organisation it is linked to. */
    public record PartyData(String partyRole, String partyName, UUID contactId, UUID organizationId,
                            boolean primary) {
    }

    /** The workspace of the contract. */
    public record WorkspaceData(String name) {
    }

    /** An organisation with its address. */
    public record OrganizationData(String name, String street, String houseNumber, String postalCode,
                                   String city) {
    }

    /** A contact with its address. */
    public record ContactData(String displayName, String firstName, String lastName,
                              String organizationName, String email, String phone, String street,
                              String houseNumber, String postalCode, String city) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private ContractTemplateValues() {
    }

    /**
     * Resolves the value for every field of the template, keyed by the field's placeholder (in lower case,
     * which is exactly how the renderer looks values up).
     *
     * @param parties       in creation order; the primary flag decides which one {@code Partei.*} addresses
     * @param organizations the organisations of the contract and its parties, keyed by id
     */
    public static Map<String, String> values(ContractData contract, List<PartyData> parties,
                                             WorkspaceData workspace, OrganizationData organization,
                                             Map<UUID, OrganizationData> organizations,
                                             Map<UUID, ContactData> contacts, List<Field> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : fields == null ? List.<Field>of() : fields) {
            String source = field.sourceEntity() == null ? "" : field.sourceEntity().toUpperCase(Locale.ROOT);
            String path = field.sourcePath() == null ? "" : field.sourcePath();
            String resolved = switch (source) {
                case "VERTRAG" -> fromContract(contract, path);
                case "PARTEI" -> fromParties(parties, contacts, organizations, path);
                case "WERK" -> fromWorkspace(workspace, organization, path);
                case "KUNDE" -> fromCustomer(parties, contacts, path);
                default -> null;
            };
            if (resolved != null && !resolved.isBlank()) {
                values.put(field.placeholder().toLowerCase(Locale.ROOT), resolved);
                values.putIfAbsent(field.fieldKey().toLowerCase(Locale.ROOT), resolved);
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ VERTRAG

    private static String fromContract(ContractData contract, String path) {
        if (contract == null) {
            return null;
        }
        return switch (normalize(path)) {
            case "title" -> contract.title();
            case "nummer", "contractnumber" -> contract.contractNumber();
            case "typ", "type", "contracttype" -> contract.contractType();
            case "status" -> contract.status();
            case "beschreibung", "description" -> contract.description();
            case "start", "startdate" -> format(contract.startDate());
            case "ende", "enddate" -> format(contract.endDate());
            case "unterzeichnetam", "signeddate" -> format(contract.signedDate());
            case "autoverlaengerung", "autorenewal" -> yesNo(contract.autoRenewal());
            case "verlaengerungsmonate", "renewalperiodmonths" -> number(contract.renewalPeriodMonths());
            case "kuendigungsfrist", "noticeperiod" -> noticePeriod(contract.noticePeriodValue(),
                    contract.noticePeriodUnit());
            case "kuendigungbis", "noticedeadlinedate" -> format(contract.noticeDeadlineDate());
            case "betrag", "costamount" -> amount(contract.costAmount());
            case "waehrung", "costcurrency" -> contract.costCurrency();
            case "zahlungsintervall", "costinterval" -> contract.costInterval();
            case "laufzeit", "term" -> term(contract.startDate(), contract.endDate());
            default -> null;
        };
    }

    // ------------------------------------------------------------------ PARTEI

    private static String fromParties(List<PartyData> parties, Map<UUID, ContactData> contacts,
                                      Map<UUID, OrganizationData> organizations, String path) {
        List<String> segments = split(path);
        if (segments.isEmpty()) {
            return null;
        }
        String first = segments.getFirst();
        String attribute = segments.size() > 1 ? segments.get(1) : first;
        PartyData party = roleKeyOf(first) != null
                ? firstPartyWithRole(parties, roleKeyOf(first))
                : primaryParty(parties);
        if (party == null) {
            return null;
        }
        return switch (normalize(attribute)) {
            case "name" -> party.partyName();
            case "rolle", "role" -> party.partyRole();
            case "anschrift", "address", "adresse" -> addressOf(contacts.get(party.contactId()),
                    party.organizationId() == null ? null : organizations.get(party.organizationId()));
            case "email" -> contactValue(contacts.get(party.contactId()), true);
            case "telefon", "phone" -> contactValue(contacts.get(party.contactId()), false);
            default -> null;
        };
    }

    /**
     * The role a template addresses in a path segment. Both the German word a document would use
     * ({@code Gegenpartei}) and the stored role are accepted.
     */
    static String roleKeyOf(String segment) {
        return switch (normalize(segment)) {
            case "ownside", "eigene", "eigeneseite", "vertragspartner" -> "OWN_SIDE";
            case "counterparty", "gegenpartei", "gegenseite" -> "COUNTERPARTY";
            case "guarantor", "buerge", "buergin" -> "GUARANTOR";
            case "agent", "vermittler" -> "AGENT";
            case "witness", "zeuge" -> "WITNESS";
            case "other", "sonstige" -> "OTHER";
            default -> null;
        };
    }

    private static PartyData primaryParty(List<PartyData> parties) {
        if (parties == null || parties.isEmpty()) {
            return null;
        }
        return parties.stream().filter(PartyData::primary).findFirst().orElseGet(() -> parties.getFirst());
    }

    private static PartyData firstPartyWithRole(List<PartyData> parties, String role) {
        if (parties == null) {
            return null;
        }
        return parties.stream().filter(party -> role.equalsIgnoreCase(
                party.partyRole() == null ? "" : party.partyRole())).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ WERK / KUNDE

    private static String fromWorkspace(WorkspaceData workspace, OrganizationData organization,
                                        String path) {
        return switch (normalize(path)) {
            case "name" -> workspace == null ? null : workspace.name();
            case "adresse", "anschrift", "address" -> addressOf(null, organization);
            default -> null;
        };
    }

    private static String fromCustomer(List<PartyData> parties, Map<UUID, ContactData> contacts,
                                       String path) {
        ContactData customer = customerContact(parties, contacts);
        if (customer == null) {
            return null;
        }
        return switch (normalize(path)) {
            case "name" -> customer.displayName() != null && !customer.displayName().isBlank()
                    ? customer.displayName()
                    : join(customer.firstName(), customer.lastName());
            case "vorname", "firstname" -> customer.firstName();
            case "nachname", "lastname" -> customer.lastName();
            case "organisation", "organization", "firma" -> customer.organizationName();
            case "anschrift", "address", "adresse" -> addressOf(customer, null);
            case "email" -> customer.email();
            case "telefon", "phone" -> customer.phone();
            default -> null;
        };
    }

    /**
     * The customer a template addresses: the contact of the COUNTERPARTY party when there is one, else the
     * contact of the first party that carries one.
     */
    private static ContactData customerContact(List<PartyData> parties, Map<UUID, ContactData> contacts) {
        if (contacts == null || contacts.isEmpty() || parties == null) {
            return null;
        }
        return parties.stream()
                .filter(party -> party.contactId() != null && contacts.containsKey(party.contactId()))
                .min((left, right) -> Integer.compare(rank(left), rank(right)))
                .map(party -> contacts.get(party.contactId()))
                .orElse(null);
    }

    /** A COUNTERPARTY contact is the customer; any other party's contact is the fallback. */
    private static int rank(PartyData party) {
        return "COUNTERPARTY".equalsIgnoreCase(party.partyRole() == null ? "" : party.partyRole()) ? 0 : 1;
    }

    // ------------------------------------------------------------------ formatting helpers

    private static String addressOf(ContactData contact, OrganizationData organization) {
        if (contact != null) {
            String street = join(contact.street(), contact.houseNumber());
            String city = join(contact.postalCode(), contact.city());
            if (!street.isBlank() || !city.isBlank()) {
                return join(street, city);
            }
        }
        if (organization != null) {
            String street = join(organization.street(), organization.houseNumber());
            String city = join(organization.postalCode(), organization.city());
            if (!street.isBlank() || !city.isBlank()) {
                return join(street, city);
            }
        }
        return null;
    }

    private static String contactValue(ContactData contact, boolean email) {
        if (contact == null) {
            return null;
        }
        return email ? contact.email() : contact.phone();
    }

    private static String noticePeriod(Integer value, String unit) {
        if (value == null) {
            return null;
        }
        String unitWord = switch (unit == null ? "" : unit.toUpperCase(Locale.ROOT)) {
            case "DAYS" -> value == 1 ? "Tag" : "Tage";
            case "WEEKS" -> value == 1 ? "Woche" : "Wochen";
            case "MONTHS" -> value == 1 ? "Monat" : "Monate";
            case "YEARS" -> value == 1 ? "Jahr" : "Jahre";
            default -> unit == null ? "" : unit;
        };
        return (value + " " + unitWord).strip();
    }

    private static String term(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return null;
        }
        if (start == null) {
            return "bis " + format(end);
        }
        if (end == null) {
            return "ab " + format(start);
        }
        return format(start) + " – " + format(end);
    }

    private static String amount(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString().replace('.', ',');
    }

    private static String number(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String yesNo(Boolean value) {
        return value == null ? null : (value ? "Ja" : "Nein");
    }

    private static String format(LocalDate date) {
        return date == null ? null : DATE.format(date);
    }

    /** Lower-cased, without dots or spaces, so {@code Gegenpartei.Name} and {@code gegenpartei.name} match. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }

    private static List<String> split(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return List.of(path.trim().split("\\."));
    }

    private static String join(String left, String right) {
        boolean hasLeft = left != null && !left.isBlank();
        boolean hasRight = right != null && !right.isBlank();
        if (hasLeft && hasRight) {
            return left.strip() + " " + right.strip();
        }
        return hasLeft ? left.strip() : hasRight ? right.strip() : "";
    }
}
