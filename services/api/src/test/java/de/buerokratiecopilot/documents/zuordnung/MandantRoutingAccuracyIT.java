package de.buerokratiecopilot.documents.zuordnung;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.contacts.identifiers.IdentifierKind;
import de.buerokratiecopilot.contacts.identifiers.IdentifierNormalizer;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.BelegStatus;
import de.buerokratiecopilot.documents.zordnung.MandantRouting;
import de.buerokratiecopilot.exports.DatevExportService;
import de.buerokratiecopilot.exports.DatevExportService.ExportResult;
import de.buerokratiecopilot.exports.DatevExportService.SkippedDocument;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The acceptance criterion §11.7 as an executable test: 100 vouchers of 10 clients through one mailbox.
 *
 * <p>The specification asks for three things at once, and they pull in different directions. At least 95 % of
 * the automatic assignments must be right, every doubtful voucher must stay "Ohne Mandant", and no voucher
 * without a client may reach a DATEV batch. A matcher that guesses more would break the second; one that
 * guesses nothing would make the first vacuous. The corpus therefore contains three groups:</p>
 *
 * <ul>
 *   <li>80 vouchers with at least one signal at or above the threshold - every one has to be assigned to its
 *       client, and at most 5 % of the assignments may be wrong ({@link MandantRouting#CONFIDENCE_THRESHOLD});
 *       </li>
 *   <li>10 vouchers whose only signal is one company name (0.80) - deliberately below the threshold, they must
 *       stay "Ohne Mandant" with {@code zuordnungs_status = 'UNKLAR'};</li>
 *   <li>10 vouchers whose company name belongs to two clients equally - they must be reported as ambiguous, not
 *       assigned to the first one alphabetically.</li>
 * </ul>
 *
 * <p>The shared company name is the ambiguity case the schema can express: {@code contact_identifiers} has a
 * unique constraint on {@code (tenant_id, kind, normalized_value)}, so one IBAN cannot belong to two clients at
 * the same time. Duplicate client names are exactly what a real Kanzlei list contains and exactly what the
 * "ask a person" rule is for.</p>
 *
 * <p><strong>Determinism.</strong> Every e-mail, IBAN, VAT id, document number and amount is derived from the
 * index of the client or document. There is no random seed, no clock-dependent value and no dependency on the
 * order in which the database returns contacts that carry different scores. The only random value is the
 * registration e-mail, which isolates this test from the other integration tests and cannot influence the
 * routing.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class MandantRoutingAccuracyIT {

    private static final int MANDANT_COUNT = 10;
    private static final int DOCUMENT_COUNT = 100;
    private static final int AMBIGUOUS_COUNT = 10;
    private static final int WEAK_COUNT = 10;
    private static final int ASSIGNED_COUNT = DOCUMENT_COUNT - AMBIGUOUS_COUNT - WEAK_COUNT;

    private static final LocalDate PERIOD_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_TO = LocalDate.of(2026, 1, 31);

    /** A supplier that is deliberately no client of this account. */
    private static final String UNRELATED_SUPPLIER = "Bürobedarf Meyer e.K.";

    /**
     * The day-to-day names of the ten clients. The six organisations are grouped into three pairs below; the
     * four people are the "part natural persons" of §11.7.
     */
    private static final String[] UNIQUE_NAMES = {
            "Nordstern Handel GmbH",
            "Lindenhof Immobilien GmbH",
            "Bergmann Werkzeugbau GmbH",
            "Rosenweg Gastronomie GmbH",
            "Kessler Logistik GmbH",
            "Hansa Büroservice GmbH",
            "Anna Schmidt",
            "Tobias Wagner",
            "Miriam Neumann",
            "Jürgen Müller"
    };

    /**
     * The legal name two mandates share. This is the ambiguity signal: two contacts with the same
     * {@code organization_name} match a letter equally well, so the platform must not choose one.
     */
    private static final String[] SHARED_NAMES = {
            "Sonnenblick Verwaltung GmbH",
            "Sonnenblick Verwaltung GmbH",
            "Nordlicht Beteiligungen GmbH",
            "Nordlicht Beteiligungen GmbH",
            "Hansa Grundbesitz GmbH",
            "Hansa Grundbesitz GmbH",
            null, null, null, null
    };

    private static final boolean[] PERSON = {false, false, false, false, false, false, true, true, true, true};

    /** How many ordinary vouchers each client has: the assigned ones plus its single weak-name voucher. */
    private static final int[] NORMAL_DOCUMENTS = {8, 8, 8, 8, 9, 9, 10, 10, 10, 10};

    /** How many shared-name vouchers each client contributes; the three pairs carry 4 + 4 + 2. */
    private static final int[] AMBIGUOUS_DOCUMENTS = {2, 2, 2, 2, 1, 1, 0, 0, 0, 0};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MandantRoutingService routing;

    @Autowired
    private DocumentService documents;

    @Autowired
    private DatevExportService datevExport;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private record Mandant(UUID id, String uniqueName, String sharedName, String email, String iban,
                           String vat) {
    }

    private enum Expectation {
        /** The voucher carries a signal at or above the threshold and must be assigned. */
        ASSIGNED,
        /** The voucher carries only one company name (0.80) and must stay below the threshold. */
        BELOW_THRESHOLD,
        /** Two clients match equally well and a person has to decide. */
        AMBIGUOUS
    }

    private record ExpectedDocument(UUID id, String number, UUID expectedMandant, Expectation expectation) {
    }

    private record Corpus(List<Mandant> mandants, List<ExpectedDocument> documents) {
    }

    private record StoredRouting(UUID mandantId, String status, BigDecimal confidence, String belegStatus) {
    }

    private record Payload(String sender, String details, String text) {
    }

    /** The signal combinations §11.7 asks for: strong alone, strong with the name, and one weak name alone. */
    private enum Variant {
        EMAIL,
        IBAN,
        VAT,
        EMAIL_NAME,
        IBAN_NAME,
        VAT_EMAIL,
        IBAN_VAT,
        EMAIL_IBAN,
        EMAIL_VAT_NAME,
        NAME_ONLY
    }

    // ------------------------------------------------------------------ the criterion

    @Test
    @DisplayName("§11.7: 100 vouchers, 10 clients, one mailbox – ≥95 % correct, all doubtful vouchers open, none exported open")
    void hundredVouchersOfTenClients() throws Exception {
        assertThat(MandantRouting.CONFIDENCE_THRESHOLD)
                .as("§5.2: the acceptance threshold stays 0.9; this test must not be made green by lowering it")
                .isEqualTo(0.9);

        Account account = register();
        Corpus corpus = buildCorpus(account);

        assertThat(corpus.mandants()).hasSize(MANDANT_COUNT);
        assertThat(corpus.documents()).hasSize(DOCUMENT_COUNT);

        List<ExpectedDocument> assignedDocuments = corpus.documents().stream()
                .filter(document -> document.expectation() == Expectation.ASSIGNED)
                .toList();
        List<ExpectedDocument> weakDocuments = corpus.documents().stream()
                .filter(document -> document.expectation() == Expectation.BELOW_THRESHOLD)
                .toList();
        List<ExpectedDocument> ambiguousDocuments = corpus.documents().stream()
                .filter(document -> document.expectation() == Expectation.AMBIGUOUS)
                .toList();
        assertThat(assignedDocuments).hasSize(ASSIGNED_COUNT);
        assertThat(weakDocuments).hasSize(WEAK_COUNT);
        assertThat(ambiguousDocuments).hasSize(AMBIGUOUS_COUNT);

        // The same service the processing pipeline calls, one document at a time, in the same tenant scope.
        Map<UUID, MandantRoutingService.RoutingResult> results = routeAll(account.tenantId(), corpus);
        Map<UUID, StoredRouting> stored = storedRouting(account.tenantId());

        long autoAssigned = stored.values().stream().filter(row -> row.mandantId() != null).count();
        long correct = assignedDocuments.stream()
                .filter(document -> document.expectedMandant().equals(stored.get(document.id()).mandantId()))
                .count();
        long wrong = autoAssigned - correct;
        double precision = autoAssigned == 0 ? 0.0 : (double) correct / autoAssigned;

        long ambiguousWithoutMandant = ambiguousDocuments.stream()
                .filter(document -> stored.get(document.id()).mandantId() == null)
                .count();
        long weakWithoutMandant = weakDocuments.stream()
                .filter(document -> stored.get(document.id()).mandantId() == null)
                .count();
        double ambiguousRate = (double) ambiguousWithoutMandant / ambiguousDocuments.size();

        List<ExpectedDocument> assignedButOpen = assignedDocuments.stream()
                .filter(document -> stored.get(document.id()).mandantId() == null)
                .toList();

        System.out.printf(Locale.ROOT, """
                §11.7 routing acceptance
                  documents: %d, mandants: %d
                  AUTO-assigned: %d/%d
                  correct AUTO assignments: %d/%d = %.2f %% (criterion: >= 95 %%)
                  wrong AUTO assignments: %d
                  ambiguous documents without Mandant: %d/%d = %.2f %% (criterion: 100 %%)
                  weak-name documents without Mandant: %d/%d
                """,
                corpus.documents().size(), corpus.mandants().size(),
                autoAssigned, corpus.documents().size(),
                correct, autoAssigned, precision * 100.0,
                wrong,
                ambiguousWithoutMandant, ambiguousDocuments.size(), ambiguousRate * 100.0,
                weakWithoutMandant, weakDocuments.size());

        // §11.7(a): every unambiguous voucher has to be assigned, otherwise the percentage has no denominator.
        assertThat(assignedButOpen).extracting(ExpectedDocument::number)
                .as("Every voucher carrying a signal at or above %.2f must be assigned automatically; "
                        + "these stayed open: %s", MandantRouting.CONFIDENCE_THRESHOLD,
                        assignedButOpen.stream().map(ExpectedDocument::number).toList())
                .isEmpty();
        assertThat(precision)
                .as("§11.7(a): correct automatic assignments %d/%d = %.2f %% (minimum 95 %%)",
                        correct, autoAssigned, precision * 100.0)
                .isGreaterThanOrEqualTo(0.95);

        // §11.7(b): 100 % of the doubtful vouchers stay "Ohne Mandant" and are visibly marked as unclear.
        assertThat(ambiguousWithoutMandant)
                .as("§11.7(b): ambiguous vouchers without Mandant %d/%d = %.2f %% (must be 100 %%)",
                        ambiguousWithoutMandant, ambiguousDocuments.size(), ambiguousRate * 100.0)
                .isEqualTo(ambiguousDocuments.size());
        assertThat(ambiguousDocuments).allSatisfy(document -> {
            StoredRouting row = stored.get(document.id());
            assertThat(row.mandantId())
                    .as("§11.7(b): ambiguous voucher %s must not be assigned to anybody", document.number())
                    .isNull();
            assertThat(row.status())
                    .as("§11.7(b): ambiguous voucher %s must be visible as UNKLAR", document.number())
                    .isEqualTo("UNKLAR");
            assertThat(results.get(document.id()).status())
                    .as("ambiguous voucher %s must be reported as AMBIGUOUS", document.number())
                    .isEqualTo(MandantRoutingService.Status.AMBIGUOUS);
        });

        // The weak-name group is not "ambiguous" in the result, but it must not be guessed either.
        assertThat(weakWithoutMandant)
                .as("weak-name vouchers without Mandant %d/%d", weakWithoutMandant, weakDocuments.size())
                .isEqualTo(weakDocuments.size());
        assertThat(weakDocuments).allSatisfy(document -> {
            StoredRouting row = stored.get(document.id());
            assertThat(row.mandantId())
                    .as("a single company name (0.80) must not assign voucher %s", document.number())
                    .isNull();
            assertThat(row.status())
                    .as("weak-name voucher %s must stay UNKLAR", document.number())
                    .isEqualTo("UNKLAR");
            assertThat(results.get(document.id()).status())
                    .as("weak-name voucher %s must be reported as BELOW_THRESHOLD", document.number())
                    .isEqualTo(MandantRoutingService.Status.BELOW_THRESHOLD);
        });

        List<UUID> openDocumentIds = corpus.documents().stream()
                .filter(document -> document.expectation() != Expectation.ASSIGNED)
                .map(ExpectedDocument::id)
                .toList();

        // §11.7(c), first half: the status machine itself refuses EXPORTIERT without a client.
        for (ExpectedDocument document : corpus.documents().stream()
                .filter(entry -> entry.expectation() != Expectation.ASSIGNED)
                .toList()) {
            assertThatThrownBy(() -> RequestContext.runAs(account.tenantId(),
                    () -> documents.changeBelegStatus(document.id(), BelegStatus.EXPORTIERT)))
                    .as("§11.7(c): voucher %s without a Mandant must be refused by changeBelegStatus",
                            document.number())
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                        assertThat(exception.getMessage()).contains("Mandanten");
                    });
        }

        // §11.7(c), second half: the batch builder leaves them out and names every one of them.
        ExportResult export = RequestContext.runAs(account.tenantId(),
                () -> datevExport.create(account.tenantId(), account.userId(), PERIOD_FROM, PERIOD_TO, null));

        assertThat(export.belegCount())
                .as("§11.7(c): only vouchers with a Mandant may enter the batch; %d were open",
                        openDocumentIds.size())
                .isEqualTo(ASSIGNED_COUNT);
        assertThat(export.skipped())
                .as("§11.7(c): every voucher without a Mandant must be reported as skipped")
                .hasSize(openDocumentIds.size());
        assertThat(export.skipped()).allSatisfy(skipped -> assertThat(skipped.reason())
                .as("§11.7(c): the reason for an open voucher is '%s'", DatevExportService.REASON_NO_MANDANT)
                .isEqualTo(DatevExportService.REASON_NO_MANDANT));
        assertThat(export.skipped()).extracting(SkippedDocument::documentId)
                .as("§11.7(c): exactly the vouchers without a Mandant are skipped")
                .containsExactlyInAnyOrderElementsOf(openDocumentIds);

        String csv = new String(RequestContext.runAs(account.tenantId(),
                () -> datevExport.download(account.tenantId(), export.id()).content()), StandardCharsets.UTF_8);
        assertThat(corpus.documents().stream()
                .filter(document -> document.expectation() != Expectation.ASSIGNED)
                .toList()).allSatisfy(document -> assertThat(csv)
                .as("§11.7(c): voucher %s without a Mandant must not appear in the DATEV file",
                        document.number())
                .doesNotContain(document.number()));
        assertThat(assignedDocuments).allSatisfy(document -> assertThat(csv)
                .as("the assigned voucher %s belongs in the DATEV file", document.number())
                .contains(document.number()));

        List<Map<String, Object>> batchItems = RequestContext.runAs(account.tenantId(),
                () -> jdbc.queryForList("""
                        select document_id, status, error_message
                          from export_batch_items
                         where tenant_id = ? and batch_id = ?
                        """, account.tenantId(), export.id()));
        assertThat(batchItems).hasSize(DOCUMENT_COUNT);
        assertThat(batchItems).filteredOn(item -> "OK".equals(item.get("status")))
                .extracting(item -> (UUID) item.get("document_id"))
                .as("§11.7(c): every assigned voucher is in the batch")
                .containsExactlyInAnyOrderElementsOf(
                        assignedDocuments.stream().map(ExpectedDocument::id).toList());
        assertThat(batchItems).filteredOn(item -> "SKIPPED".equals(item.get("status")))
                .hasSize(openDocumentIds.size())
                .allSatisfy(item -> assertThat(item.get("error_message"))
                        .isEqualTo(DatevExportService.REASON_NO_MANDANT));

        // After the batch, the exported vouchers are EXPORTIERT and the open ones are exactly where they were.
        Map<UUID, StoredRouting> afterExport = storedRouting(account.tenantId());
        long openExported = openDocumentIds.stream()
                .filter(documentId -> "EXPORTIERT".equals(afterExport.get(documentId).belegStatus()))
                .count();
        System.out.printf(Locale.ROOT, """
                §11.7 export acceptance
                  DATEV batch: %d exported, %d skipped
                  open vouchers moved to EXPORTIERT: %d
                """, export.belegCount(), export.skipped().size(), openExported);
        assertThat(openExported)
                .as("§11.7(c): no voucher without a Mandant may be exported")
                .isZero();
        assertThat(assignedDocuments).allSatisfy(document -> {
            assertThat(afterExport.get(document.id()).mandantId())
                    .as("assigned voucher %s keeps its Mandant through the export", document.number())
                    .isNotNull();
            assertThat(afterExport.get(document.id()).belegStatus())
                    .as("assigned voucher %s leaves the queue", document.number())
                    .isEqualTo("EXPORTIERT");
        });
        assertThat(openDocumentIds).allSatisfy(documentId -> {
            assertThat(afterExport.get(documentId).mandantId())
                    .as("voucher %s stays without a Mandant", documentId)
                    .isNull();
            assertThat(afterExport.get(documentId).belegStatus())
                    .as("voucher %s stays in the queue", documentId)
                    .isEqualTo("ZU_PRUEFEN");
        });

        // No automatic assignment may carry a status other than AUTO: a person has not touched these vouchers.
        assertThat(corpus.documents()).allSatisfy(document -> {
            StoredRouting row = stored.get(document.id());
            if (row.mandantId() != null) {
                assertThat(row.status())
                        .as("automatically assigned voucher %s must be stored as AUTO", document.number())
                        .isEqualTo("AUTO");
            }
        });
    }

    // ------------------------------------------------------------------ corpus

    private Corpus buildCorpus(Account account) {
        return RequestContext.runAs(account.tenantId(), () -> {
            List<Mandant> mandants = createMandants(account.tenantId());
            for (Mandant mandant : mandants) {
                jdbc.update("""
                        insert into konto_mapping (tenant_id, mandant_id, supplier, category, sollkonto,
                                                   habenkonto, steuerschluessel)
                        values (?, ?, null, null, '1200', '1600', '21')
                        """, account.tenantId(), mandant.id());
            }

            List<ExpectedDocument> documents = new ArrayList<>();
            int documentIndex = 0;
            for (int mandantIndex = 0; mandantIndex < MANDANT_COUNT; mandantIndex++) {
                Mandant mandant = mandants.get(mandantIndex);
                int sequence = 1;

                for (int variantIndex = 0; variantIndex < NORMAL_DOCUMENTS[mandantIndex] - 1; variantIndex++) {
                    Variant variant = Variant.values()[variantIndex];
                    String number = documentNumber(mandantIndex, sequence);
                    Payload payload = payload(variant, mandant, number);
                    UUID id = insertDocument(account.tenantId(), documentIndex, number, payload);
                    documents.add(new ExpectedDocument(id, number, mandant.id(), Expectation.ASSIGNED));
                    sequence++;
                    documentIndex++;
                }

                String weakNumber = documentNumber(mandantIndex, sequence);
                UUID weakId = insertDocument(account.tenantId(), documentIndex, weakNumber,
                        payload(Variant.NAME_ONLY, mandant, weakNumber));
                documents.add(new ExpectedDocument(weakId, weakNumber, mandant.id(), Expectation.BELOW_THRESHOLD));
                sequence++;
                documentIndex++;

                for (int ambiguousIndex = 0; ambiguousIndex < AMBIGUOUS_DOCUMENTS[mandantIndex]; ambiguousIndex++) {
                    String number = documentNumber(mandantIndex, sequence);
                    Payload payload = ambiguousPayload(mandant, number, ambiguousIndex % 2 == 0);
                    UUID id = insertDocument(account.tenantId(), documentIndex, number, payload);
                    documents.add(new ExpectedDocument(id, number, null, Expectation.AMBIGUOUS));
                    sequence++;
                    documentIndex++;
                }
            }

            assertThat(documentIndex)
                    .as("the corpus must contain exactly %d vouchers", DOCUMENT_COUNT)
                    .isEqualTo(DOCUMENT_COUNT);
            return new Corpus(List.copyOf(mandants), List.copyOf(documents));
        });
    }

    private List<Mandant> createMandants(UUID tenantId) {
        List<Mandant> mandants = new ArrayList<>();
        for (int index = 0; index < MANDANT_COUNT; index++) {
            String uniqueName = UNIQUE_NAMES[index];
            String sharedName = SHARED_NAMES[index];
            String email = "mandant.%02d@example.de".formatted(index);
            String iban = iban(index);
            String vat = "DE%09d".formatted(300_000_001 + index);

            UUID id;
            if (PERSON[index]) {
                int split = uniqueName.lastIndexOf(' ');
                id = jdbc.queryForObject("""
                        insert into contacts (tenant_id, contact_type, display_name, first_name, last_name,
                                              email, vat_id)
                        values (?, 'PERSON', ?, ?, ?, ?, ?)
                        returning id
                        """, UUID.class, tenantId, uniqueName, uniqueName.substring(0, split),
                        uniqueName.substring(split + 1), email, vat);
            }
            else {
                id = jdbc.queryForObject("""
                        insert into contacts (tenant_id, contact_type, display_name, organization_name,
                                              email, vat_id)
                        values (?, 'ORGANIZATION', ?, ?, ?, ?)
                        returning id
                        """, UUID.class, tenantId, uniqueName, sharedName, email, vat);
            }

            identifier(tenantId, id, IdentifierKind.EMAIL, email);
            identifier(tenantId, id, IdentifierKind.IBAN, iban);
            identifier(tenantId, id, IdentifierKind.VAT_ID, vat);
            mandants.add(new Mandant(id, uniqueName, sharedName, email, iban, vat));
        }
        return mandants;
    }

    private void identifier(UUID tenantId, UUID contactId, IdentifierKind kind, String value) {
        jdbc.update("""
                insert into contact_identifiers (tenant_id, contact_id, kind, value, normalized_value)
                values (?, ?, ?, ?, ?)
                """, tenantId, contactId, kind.name(), value, IdentifierNormalizer.normalize(kind, value));
    }

    private Payload payload(Variant variant, Mandant mandant, String number) {
        String header = "Rechnung Nr. " + number;
        String amount = "Betrag: 119,00 EUR\nZahlbar innerhalb von 14 Tagen.";
        String iban = "IBAN: " + spaced(mandant.iban());
        String vat = "USt-IdNr.: " + mandant.vat();
        String name = mandant.uniqueName();

        return switch (variant) {
            case EMAIL -> new Payload(UNRELATED_SUPPLIER, emailDetails(mandant.email()),
                    header + "\n" + amount);
            case IBAN -> new Payload(UNRELATED_SUPPLIER, "{}",
                    header + "\n" + iban + "\n" + amount);
            case VAT -> new Payload(UNRELATED_SUPPLIER, "{}",
                    header + "\n" + vat + "\n" + amount);
            case EMAIL_NAME -> new Payload(name, emailDetails(mandant.email()),
                    header + "\n" + name + "\n" + amount);
            case IBAN_NAME -> new Payload(name, "{}",
                    header + "\n" + name + "\n" + iban + "\n" + amount);
            case VAT_EMAIL -> new Payload(UNRELATED_SUPPLIER,
                    emailAndVatDetails(mandant.email(), mandant.vat()), header + "\n" + amount);
            case IBAN_VAT -> new Payload(UNRELATED_SUPPLIER, "{}",
                    header + "\n" + iban + "\n" + vat + "\n" + amount);
            case EMAIL_IBAN -> new Payload(UNRELATED_SUPPLIER, emailDetails(mandant.email()),
                    header + "\n" + iban + "\n" + amount);
            case EMAIL_VAT_NAME -> new Payload(name, emailDetails(mandant.email()),
                    header + "\n" + name + "\n" + vat + "\n" + amount);
            case NAME_ONLY -> new Payload(name, "{}", header + "\n" + name + "\n" + amount);
        };
    }

    /**
     * A voucher whose only readable signal is the legal name two clients share. Half of them carry the name in
     * the mail sender, half only in the text - both paths have to see both candidates.
     */
    private Payload ambiguousPayload(Mandant mandant, String number, boolean nameInSender) {
        String header = "Rechnung Nr. " + number;
        String amount = "Betrag: 119,00 EUR";
        if (mandant.sharedName() == null) {
            throw new IllegalStateException("Ambiguity needs a shared legal name: " + mandant.uniqueName());
        }
        if (nameInSender) {
            return new Payload(mandant.sharedName(), "{}", header + "\n" + amount);
        }
        return new Payload(UNRELATED_SUPPLIER, "{}", header + "\n" + mandant.sharedName() + "\n" + amount);
    }

    private UUID insertDocument(UUID tenantId, int index, String number, Payload payload) {
        UUID id = jdbc.queryForObject("""
                insert into documents (tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, beleg_status,
                                       document_date, document_number, sender, sender_details, extracted_text)
                values (?, ?, ?, 'application/pdf', 120, ?, 'UPLOAD', 'READY', 'NOT_REQUIRED', 'ZU_PRUEFEN',
                        ?, ?, ?, cast(? as jsonb), ?)
                returning id
                """, UUID.class, tenantId,
                "beleg-%03d.pdf".formatted(index), "beleg-%03d.pdf".formatted(index),
                "%064x".formatted(index + 1L),
                PERIOD_FROM.plusDays(index % 27L), number, payload.sender(), payload.details(), payload.text());

        String amount = "%d.00".formatted(100 + index);
        jdbc.update("""
                insert into document_extractions (document_id, field_key, field_value, normalized_value,
                                                  value_type, source_kind, source_snippet, extractor)
                values (?, 'Brutto', ?, ?, 'AMOUNT', 'TEXT', 'Gesamtbetrag laut Rechnung', 'RULE')
                """, id, amount + " EUR", amount);
        return id;
    }

    // ------------------------------------------------------------------ reading and calling

    private Map<UUID, MandantRoutingService.RoutingResult> routeAll(UUID tenantId, Corpus corpus) {
        return RequestContext.runAs(tenantId, () -> {
            Map<UUID, MandantRoutingService.RoutingResult> results = new LinkedHashMap<>();
            for (ExpectedDocument document : corpus.documents()) {
                results.put(document.id(), routing.route(document.id()));
            }
            return results;
        });
    }

    private Map<UUID, StoredRouting> storedRouting(UUID tenantId) {
        return RequestContext.runAs(tenantId, () -> jdbc.queryForList("""
                        select id, mandant_id, zuordnungs_status, mandant_confidence, beleg_status
                          from documents
                         where tenant_id = ?
                        """, tenantId).stream().collect(Collectors.toMap(
                row -> (UUID) row.get("id"),
                row -> new StoredRouting((UUID) row.get("mandant_id"), (String) row.get("zuordnungs_status"),
                        (BigDecimal) row.get("mandant_confidence"), (String) row.get("beleg_status")))));
    }

    // ------------------------------------------------------------------ setup

    private Account register() throws Exception {
        String email = "accuracy-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Mandant Routing Accuracy","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(body(registration), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(body(registration), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Account(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ deterministic identifiers

    private static String documentNumber(int mandantIndex, int sequence) {
        return "M%02d-D%03d".formatted(mandantIndex, sequence);
    }

    private static String emailDetails(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    private static String emailAndVatDetails(String email, String vat) {
        return "{\"email\":\"" + email + "\",\"vatId\":\"" + vat + "\"}";
    }

    /** Groups of four as a German letter writes an IBAN. The check digits are computed, not invented. */
    private static String spaced(String value) {
        StringBuilder out = new StringBuilder(value.length() + value.length() / 4);
        for (int index = 0; index < value.length(); index++) {
            if (index > 0 && index % 4 == 0) {
                out.append(' ');
            }
            out.append(value.charAt(index));
        }
        return out.toString();
    }

    /**
     * A valid German IBAN per client. A wrong check digit would make {@code IdentifierExtractor} ignore the
     * number silently, and the test would then claim a routing quality the platform never had to show.
     */
    private static String iban(int index) {
        String bban = "37040044" + "%010d".formatted(1_000_000_000L + index);
        String rearranged = bban + "DE00";
        StringBuilder digits = new StringBuilder();
        for (char character : rearranged.toCharArray()) {
            digits.append(Character.isDigit(character) ? String.valueOf(character)
                    : String.valueOf(character - 'A' + 10));
        }
        int checkDigits = 98 - new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
        String iban = "DE%02d%s".formatted(checkDigits, bban);
        assertThat(IdentifierNormalizer.isValidIban(iban))
                .as("the test corpus uses only valid IBANs; %s is not valid", iban)
                .isTrue();
        return iban;
    }
}
