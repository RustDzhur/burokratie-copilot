package de.buerokratiecopilot.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.contracts.ContractTemplateValues.ContactData;
import de.buerokratiecopilot.contracts.ContractTemplateValues.ContractData;
import de.buerokratiecopilot.contracts.ContractTemplateValues.OrganizationData;
import de.buerokratiecopilot.contracts.ContractTemplateValues.PartyData;
import de.buerokratiecopilot.contracts.ContractTemplateValues.WorkspaceData;
import de.buerokratiecopilot.templates.TemplateRenderer.Field;

/**
 * Verifies the contract field catalogue (V72): what a contract template may ask for, and where each value
 * comes from.
 *
 * <p>The catalogue is the promise this class makes to every template. The tests pin the vocabulary — a
 * path that resolves today must keep resolving, and one that does not exist must keep resolving to nothing
 * (which the renderer then turns into a refusal instead of a hole in the document).</p>
 */
class ContractTemplateValuesTest {

    private static final UUID COUNTERPARTY_CONTACT = UUID.randomUUID();
    private static final UUID OWN_CONTACT = UUID.randomUUID();
    private static final UUID CONTRACT_ORG = UUID.randomUUID();

    private static ContractData contract() {
        return new ContractData("Mietvertrag Lagerhalle", "V-2026-001", "RENT", "ACTIVE",
                "Lagerhalle Nord", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2025, 12, 15), true, 12, 3, "MONTHS", LocalDate.of(2026, 9, 30),
                new BigDecimal("1250.50"), "EUR", "MONTHLY");
    }

    private static List<PartyData> parties() {
        return List.of(
                new PartyData("OWN_SIDE", "Bürokratie GmbH", null, CONTRACT_ORG, true),
                new PartyData("COUNTERPARTY", "Muster Logistik AG", COUNTERPARTY_CONTACT, null, false));
    }

    private static Map<UUID, ContactData> contacts() {
        Map<UUID, ContactData> contacts = new LinkedHashMap<>();
        contacts.put(COUNTERPARTY_CONTACT, new ContactData("Muster Logistik AG", "Erika", "Muster",
                "Muster Logistik AG", "erika@muster.example", "030 123456", "Hafenstraße", "7", "10115",
                "Berlin"));
        contacts.put(OWN_CONTACT, new ContactData("Bürokratie GmbH", "Sara", "Sachlich", "Bürokratie GmbH",
                "sara@buerokratie.example", "030 999", null, null, null, null));
        return contacts;
    }

    private static Map<UUID, OrganizationData> organizations() {
        Map<UUID, OrganizationData> organizations = new LinkedHashMap<>();
        organizations.put(CONTRACT_ORG, new OrganizationData("Bürokratie GmbH", "Amtsweg", "1", "10117",
                "Berlin"));
        return organizations;
    }

    private static Map<String, String> valuesFor(List<Field> fields) {
        return ContractTemplateValues.values(contract(), parties(), new WorkspaceData("Kanzlei Mitte"),
                organizations().get(CONTRACT_ORG), organizations(), contacts(), fields);
    }

    private static Field field(String entity, String path) {
        return new Field(entity.toLowerCase() + "_" + path.replace('.', '_'), entity, path, false, null,
                "TEXT");
    }

    @Test
    @DisplayName("the Vertrag source answers the contract's own fields")
    void vertragSourceAnswersContractFields() {
        Map<String, String> values = valuesFor(List.of(
                field("VERTRAG", "Title"), field("VERTRAG", "Nummer"), field("VERTRAG", "Start"),
                field("VERTRAG", "Ende"), field("VERTRAG", "AutoVerlaengerung"),
                field("VERTRAG", "Kuendigungsfrist"), field("VERTRAG", "Betrag"),
                field("VERTRAG", "Waehrung"), field("VERTRAG", "Laufzeit"),
                field("VERTRAG", "VerlaengerungsMonate")));

        assertThat(values).containsEntry("vertrag.title", "Mietvertrag Lagerhalle")
                .containsEntry("vertrag.nummer", "V-2026-001")
                .containsEntry("vertrag.start", "01.01.2026")
                .containsEntry("vertrag.ende", "31.12.2026")
                .containsEntry("vertrag.autoverlaengerung", "Ja")
                .containsEntry("vertrag.kuendigungsfrist", "3 Monate")
                .containsEntry("vertrag.betrag", "1250,50")
                .containsEntry("vertrag.waehrung", "EUR")
                .containsEntry("vertrag.laufzeit", "01.01.2026 – 31.12.2026")
                .containsEntry("vertrag.verlaengerungsmonate", "12");
    }

    @Test
    @DisplayName("a missing value resolves to nothing instead of an empty string pretending to be data")
    void missingValueResolvesToNothing() {
        Map<String, String> values = ContractTemplateValues.values(
                new ContractData("Titel", null, "OTHER", "DRAFT", null, null, null, null, null, null,
                        null, null, null, null, "EUR", null),
                List.of(), null, null, Map.of(), Map.of(),
                List.of(field("VERTRAG", "Nummer"), field("VERTRAG", "Kuendigungsfrist"),
                        field("VERTRAG", "AutoVerlaengerung")));

        assertThat(values).doesNotContainKeys("vertrag.nummer", "vertrag.kuendigungsfrist",
                "vertrag.autoverlaengerung");
    }

    @Test
    @DisplayName("Partei without a role addresses the primary party")
    void parteiAddressesThePrimaryParty() {
        Map<String, String> values = valuesFor(List.of(
                field("PARTEI", "Name"), field("PARTEI", "Rolle"), field("PARTEI", "Anschrift")));

        assertThat(values).containsEntry("partei.name", "Bürokratie GmbH")
                .containsEntry("partei.rolle", "OWN_SIDE")
                .containsEntry("partei.anschrift", "Amtsweg 1 10117 Berlin");
    }

    @Test
    @DisplayName("a role in the path addresses that party, in German or as stored")
    void roleInPathAddressesThatParty() {
        Map<String, String> values = valuesFor(List.of(
                field("PARTEI", "Gegenpartei.Name"), field("PARTEI", "Gegenpartei.Anschrift"),
                field("PARTEI", "Gegenpartei.Email"), field("PARTEI", "COUNTERPARTY.Name")));

        assertThat(values).containsEntry("partei.gegenpartei.name", "Muster Logistik AG")
                .containsEntry("partei.gegenpartei.anschrift", "Hafenstraße 7 10115 Berlin")
                .containsEntry("partei.gegenpartei.email", "erika@muster.example")
                .containsEntry("partei.counterparty.name", "Muster Logistik AG");
    }

    @Test
    @DisplayName("the primary flag decides, not the order in the list")
    void primaryFlagDecides() {
        List<PartyData> shuffled = List.of(
                new PartyData("COUNTERPARTY", "Zuerst AG", COUNTERPARTY_CONTACT, null, false),
                new PartyData("OWN_SIDE", "Bürokratie GmbH", null, CONTRACT_ORG, true));
        Map<String, String> values = ContractTemplateValues.values(contract(), shuffled,
                new WorkspaceData("Werk"), null, organizations(), contacts(),
                List.of(field("PARTEI", "Name")));

        assertThat(values).containsEntry("partei.name", "Bürokratie GmbH");
    }

    @Test
    @DisplayName("Werk answers the workspace name and the organisation's address")
    void werkAnswersWorkspaceAndAddress() {
        Map<String, String> values = valuesFor(List.of(
                field("WERK", "Name"), field("WERK", "Adresse")));

        assertThat(values).containsEntry("werk.name", "Kanzlei Mitte")
                .containsEntry("werk.adresse", "Amtsweg 1 10117 Berlin");
    }

    @Test
    @DisplayName("Kunde answers the counterparty contact, and the contract's contact as a fallback")
    void kundeAnswersTheCustomerContact() {
        Map<String, String> values = valuesFor(List.of(
                field("KUNDE", "Name"), field("KUNDE", "Organisation"), field("KUNDE", "Anschrift"),
                field("KUNDE", "Email")));

        assertThat(values).containsEntry("kunde.name", "Muster Logistik AG")
                .containsEntry("kunde.organisation", "Muster Logistik AG")
                .containsEntry("kunde.anschrift", "Hafenstraße 7 10115 Berlin")
                .containsEntry("kunde.email", "erika@muster.example");

        // Without a party carrying the contact, the contract's own counterparty contact is the customer.
        Map<String, String> fallback = ContractTemplateValues.values(contract(), List.of(),
                null, null, Map.of(), contacts(),
                List.of(field("KUNDE", "Name")));
        assertThat(fallback).isEmpty();
    }

    @Test
    @DisplayName("a path outside the catalogue resolves to nothing")
    void unknownPathResolvesToNothing() {
        Map<String, String> values = valuesFor(List.of(
                field("VERTRAG", "Horoskop"), field("PARTEI", "Kontonummer"), field("WERK", "Farbe")));

        assertThat(values).isEmpty();
    }

    @Test
    @DisplayName("paths are matched case-insensitively and without separators")
    void pathsAreMatchedLoosely() {
        Map<String, String> values = valuesFor(List.of(
                field("VERTRAG", "kuendigungs-frist"), field("VERTRAG", "auto_renewal")));

        assertThat(values).containsEntry("vertrag.kuendigungs-frist", "3 Monate")
                .containsEntry("vertrag.auto_renewal", "Ja");
    }

    @Test
    @DisplayName("a value is also stored under the field key, as the renderer looks it up")
    void valueIsStoredUnderFieldKeyToo() {
        Map<String, String> values = valuesFor(List.of(
                new Field("vertragstitel", "VERTRAG", "Title", false, null, "TEXT")));

        assertThat(values).containsEntry("vertragstitel", "Mietvertrag Lagerhalle")
                .containsEntry("vertrag.title", "Mietvertrag Lagerhalle");
    }
}
