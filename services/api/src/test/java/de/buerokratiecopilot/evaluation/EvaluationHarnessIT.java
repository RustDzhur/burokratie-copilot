package de.buerokratiecopilot.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.ai.security.InjectionDetector;
import de.buerokratiecopilot.contacts.identifiers.IdentifierExtractor;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.onboarding.OnboardingService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The evaluation set (task RD-09): synthetic German documents with known answers, run through the real pipeline, measured.
 *
 * <p>The documents are invented; none is a real person's. The harness measures what the platform does <em>without</em> an AI
 * model (the rules are the first line), reports the numbers, and fails only on the properties that must always hold. Every
 * other number is a measurement to improve against; a threshold is fixed here only after it was measured (see
 * docs/testing/test-strategy.md).</p>
 *
 * <p>Measured: the share of documents whose type is right, how many were accepted without a person and how often that was
 * wrong (the number that matters most), the fields read and their values, the identifiers (IBAN with check digits, VAT id,
 * tax number), the share of read values that carry their source, and how embedded instructions are treated.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class EvaluationHarnessIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private OnboardingService onboarding;
    @Autowired
    private DocumentService documents;
    @Autowired
    private DocumentProcessingPipeline pipeline;
    @Autowired
    private JdbcTemplate jdbc;

    /** What happened to one document. */
    private record Result(String id, String category, String expectedType, List<String> acceptable, String classified,
                          Double confidence, String accepted, boolean typeKnown, Map<String, String> missingFields,
                          Map<String, String> wrongFields, int fieldsExpected, int fieldsRight, int fieldsRead,
                          int fieldsWithSource, Set<String> identifiersMissing, Set<String> identifiersInvented,
                          int identifiersExpected, int identifiersFound, boolean injectionFlag, boolean injectionDetected,
                          String error) {
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("development set: measures type, fields, identifiers, sources and embedded instructions, and enforces the safety properties")
    void measureDevelopmentSet() throws Exception {
        List<Result> results = run("/eval/german-documents.json", "german-report.md", "development set (rules were tuned against it)");

        // What must always hold, whatever the quality of the rules.
        assertThat(results).filteredOn(r -> r.error() != null).as("no document may break the pipeline").isEmpty();
        assertThat(results).filteredOn(Result::injectionFlag).allSatisfy(r -> assertThat(r.injectionDetected())
                .as("embedded instruction in " + r.id() + " must be detected").isTrue());
        assertThat(results).filteredOn(r -> !r.injectionFlag()).allSatisfy(r -> assertThat(r.injectionDetected())
                .as("a normal document must not be flagged: " + r.id()).isFalse());
        assertThat(results).filteredOn(r -> r.category().equals("misc") && r.expectedType() == null)
                .allSatisfy(r -> assertThat(r.accepted()).as("a document that is nothing (empty, garbled) must not be accepted: " + r.id()).isNull());
        // Measured on 2026-10-15 after the tuning of V55/V56; a fall below this is a regression of the rules.
        assertThat(wrongAutomation(results)).as("an automatically accepted type must be right").isZero();
        assertThat(typeAccuracy(results)).as("share of right types").isGreaterThanOrEqualTo(0.97);
    }

    @Test
    @DisplayName("holdout set: documents written afterwards, in other words, measured but not enforced - the honest figure")
    void measureHoldoutSet() throws Exception {
        List<Result> results = run("/eval/german-documents-holdout.json", "german-report-holdout.md", "holdout set (not used for tuning)");
        assertThat(results).filteredOn(r -> r.error() != null).as("no document may break the pipeline").isEmpty();
        // The one property that must hold on unseen documents as well: nothing that is not a document gets accepted.
        assertThat(results).filteredOn(r -> r.expectedType() == null && r.acceptable().isEmpty() && !r.injectionFlag())
                .allSatisfy(r -> assertThat(r.accepted()).as("garbled text must not be accepted: " + r.id()).isNull());
    }

    private static double typeAccuracy(List<Result> results) {
        List<Result> typed = results.stream().filter(r -> r.expectedType() != null && r.typeKnown()).toList();
        return typed.isEmpty() ? 1.0 : (double) typed.stream().filter(r -> typeRight(r, r.classified())).count() / typed.size();
    }

    private static long wrongAutomation(List<Result> results) {
        return results.stream().filter(r -> r.expectedType() != null && r.typeKnown() && r.accepted() != null && !typeRight(r, r.accepted())).count();
    }

    private List<Result> run(String resource, String reportFile, String title) throws Exception {
        JsonNode set = JSON.readTree(getClass().getResourceAsStream(resource));
        var created = onboarding.onboard(new OnboardingService.Request(
                "eval-" + UUID.randomUUID().toString().substring(0, 12), "Evaluation", "COMPANY",
                "eval-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de", "Erika Mustermann",
                "de", "Europe/Berlin", "DE", "Evaluation"));
        UUID tenantId = created.tenantId();
        Set<String> knownTypes = new TreeSet<>(jdbc.queryForList("select code from document_types", String.class));

        List<Result> results = new ArrayList<>();
        for (JsonNode item : set) {
            results.add(evaluate(item, tenantId, knownTypes));
        }
        assertThat(results).as("every document of the set was processed").hasSize(set.size());

        String report = report(results, knownTypes, title);
        Path out = Path.of("target", "eval");
        Files.createDirectories(out);
        Files.writeString(out.resolve(reportFile), report, StandardCharsets.UTF_8);
        System.out.println(report);
        return results;
    }

    private Result evaluate(JsonNode item, UUID tenantId, Set<String> knownTypes) {
        String id = item.get("id").asText();
        String text = item.get("text").asText();
        String expectedType = item.get("expectedType").isNull() ? null : item.get("expectedType").asText();
        List<String> acceptable = new ArrayList<>();
        item.get("acceptableTypes").forEach(node -> acceptable.add(node.isNull() ? null : node.asText()));
        boolean typeKnown = expectedType == null || knownTypes.contains(expectedType);

        String classified = null;
        Double confidence = null;
        String accepted = null;
        Map<String, String> found = new LinkedHashMap<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        int withSource = 0;
        String error = null;
        try {
            UUID documentId = RequestContext.runAs(tenantId, () -> documents.receive(new DocumentService.UploadRequest(
                    new MockMultipartFile("file", item.get("filename").asText(), "text/plain",
                            (text + "\n" + id).getBytes(StandardCharsets.UTF_8)), null, null, Document.Source.UPLOAD, null))
                    .document().getId());
            RequestContext.runAs(tenantId, () -> pipeline.advance(documentId));
            List<Map<String, Object>> current = RequestContext.runAs(tenantId, () -> jdbc.queryForList("""
                    select t.code, c.confidence from document_classifications c
                    join document_types t on t.id = c.document_type_id where c.document_id = ? and c.is_current
                    """, documentId));
            if (!current.isEmpty()) {
                classified = (String) current.get(0).get("code");
                confidence = current.get(0).get("confidence") == null ? null : ((Number) current.get(0).get("confidence")).doubleValue();
            }
            List<String> acceptedType = RequestContext.runAs(tenantId, () -> jdbc.queryForList("""
                    select t.code from documents d join document_types t on t.id = d.document_type_id where d.id = ?
                    """, String.class, documentId));
            accepted = acceptedType.isEmpty() ? null : acceptedType.get(0);
            for (Map<String, Object> row : RequestContext.runAs(tenantId, () -> jdbc.queryForList(
                    "select field_key, field_value, normalized_value, source_snippet, extractor from document_extractions where document_id = ?",
                    documentId))) {
                String key = (String) row.get("field_key");
                found.put(key, clean((String) row.get("field_value")));
                normalized.put(key, clean((String) row.get("normalized_value")));
                if (row.get("source_snippet") != null && !clean((String) row.get("source_snippet")).isEmpty()) {
                    withSource++;
                }
            }
        }
        catch (RuntimeException ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }

        Map<String, String> missing = new LinkedHashMap<>();
        Map<String, String> wrong = new LinkedHashMap<>();
        int right = 0;
        JsonNode expectedFields = item.get("expectedFields");
        int expectedCount = expectedFields.size();
        for (var entries = expectedFields.fields(); entries.hasNext(); ) {
            var entry = entries.next();
            String expected = clean(entry.getValue().asText());
            if (!found.containsKey(entry.getKey())) {
                missing.put(entry.getKey(), expected);
            }
            else if (expected.equals(normalized.get(entry.getKey())) || expected.equals(found.get(entry.getKey()))) {
                right++;
            }
            else {
                wrong.put(entry.getKey(), expected + " <> " + found.get(entry.getKey()) + " / " + normalized.get(entry.getKey()));
            }
        }

        Map<String, Set<String>> identifiers = new LinkedHashMap<>();
        for (IdentifierExtractor.Found hit : IdentifierExtractor.extract(text)) {
            identifiers.computeIfAbsent(hit.kind().name(), k -> new TreeSet<>()).add(hit.normalized());
        }
        Set<String> identifiersMissing = new TreeSet<>();
        Set<String> identifiersInvented = new TreeSet<>();
        int identifiersExpected = 0;
        int identifiersFound = 0;
        JsonNode expectedIdentifiers = item.get("expectedIdentifiers");
        for (var kinds = expectedIdentifiers.fields(); kinds.hasNext(); ) {
            var kind = kinds.next();
            for (JsonNode value : kind.getValue()) {
                identifiersExpected++;
                if (identifiers.getOrDefault(kind.getKey(), Set.of()).contains(value.asText())) {
                    identifiersFound++;
                }
                else {
                    identifiersMissing.add(kind.getKey() + ":" + value.asText());
                }
            }
        }
        for (var kind : identifiers.entrySet()) {
            for (String value : kind.getValue()) {
                boolean expected = false;
                for (JsonNode wanted : expectedIdentifiers.path(kind.getKey())) {
                    expected |= wanted.asText().equals(value);
                }
                if (!expected) {
                    identifiersInvented.add(kind.getKey() + ":" + value);
                }
            }
        }

        boolean injectionFlag = item.get("injection").asBoolean();
        return new Result(id, item.get("category").asText(), expectedType, acceptable, classified, confidence, accepted, typeKnown,
                missing, wrong, expectedCount, right, found.size(), withSource, identifiersMissing, identifiersInvented,
                identifiersExpected, identifiersFound, injectionFlag, InjectionDetector.inspect(text).hasAttempt(), error);
    }

    private static boolean typeRight(Result r, String code) {
        return code != null && (code.equals(r.expectedType()) || r.acceptable().contains(code));
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f %% (%d/%d)", 100.0 * part / whole, part, whole);
    }

    private String report(List<Result> results, Set<String> knownTypes, String title) {
        List<Result> typed = results.stream().filter(r -> r.expectedType() != null && r.typeKnown()).toList();
        List<Result> gaps = results.stream().filter(r -> r.expectedType() != null && !r.typeKnown()).toList();
        List<Result> nothing = results.stream().filter(r -> r.expectedType() == null).toList();

        int top1 = (int) typed.stream().filter(r -> typeRight(r, r.classified())).count();
        int acceptedAny = (int) typed.stream().filter(r -> r.accepted() != null).count();
        int acceptedRight = (int) typed.stream().filter(r -> typeRight(r, r.accepted())).count();
        int acceptedWrong = acceptedAny - acceptedRight;
        int nothingAccepted = (int) nothing.stream().filter(r -> r.accepted() != null && !r.acceptable().contains(r.accepted())).count();

        int fieldsExpected = results.stream().mapToInt(Result::fieldsExpected).sum();
        int fieldsRight = results.stream().mapToInt(Result::fieldsRight).sum();
        int fieldsRead = results.stream().mapToInt(Result::fieldsRead).sum();
        int fieldsWithSource = results.stream().mapToInt(Result::fieldsWithSource).sum();
        int idExpected = results.stream().mapToInt(Result::identifiersExpected).sum();
        int idFound = results.stream().mapToInt(Result::identifiersFound).sum();
        int idInvented = results.stream().mapToInt(r -> r.identifiersInvented().size()).sum();
        long injections = results.stream().filter(Result::injectionFlag).count();
        long injectionsCaught = results.stream().filter(r -> r.injectionFlag() && r.injectionDetected()).count();
        long falseAlarms = results.stream().filter(r -> !r.injectionFlag() && r.injectionDetected()).count();

        StringBuilder out = new StringBuilder();
        out.append("# German evaluation - ").append(title).append(" - without an AI model\n\n");
        out.append("Documents: ").append(results.size()).append(" (typed ").append(typed.size()).append(", nothing-to-recognise ")
                .append(nothing.size()).append(", type missing in the taxonomy ").append(gaps.size()).append(")\n\n");
        out.append("| Measure | Value |\n|---|---|\n");
        out.append("| Type right (current classification) | ").append(percent(top1, typed.size())).append(" |\n");
        out.append("| Accepted without a person | ").append(percent(acceptedAny, typed.size())).append(" |\n");
        out.append("| Of the accepted: right | ").append(percent(acceptedRight, acceptedAny)).append(" |\n");
        out.append("| Of the accepted: WRONG (false automation) | ").append(percent(acceptedWrong, acceptedAny)).append(" |\n");
        out.append("| Documents that are nothing but were accepted | ").append(percent(nothingAccepted, nothing.size())).append(" |\n");
        out.append("| Expected field values right | ").append(percent(fieldsRight, fieldsExpected)).append(" |\n");
        out.append("| Read values that carry their source | ").append(percent(fieldsWithSource, fieldsRead)).append(" |\n");
        out.append("| Identifiers found (IBAN with check digits, VAT id, tax number, e-mail) | ").append(percent(idFound, idExpected)).append(" |\n");
        out.append("| Identifiers invented | ").append(idInvented).append(" |\n");
        out.append("| Embedded instructions detected | ").append(percent((int) injectionsCaught, (int) injections)).append(" |\n");
        out.append("| Normal documents flagged as instruction | ").append(falseAlarms).append(" |\n\n");

        out.append("## Per document\n\n| Id | Expected | Classified | Confidence | Accepted | Fields | Identifiers |\n|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            out.append("| ").append(r.id()).append(" | ").append(r.expectedType() == null ? "-" : r.expectedType())
                    .append(r.typeKnown() ? "" : " (not in taxonomy)").append(" | ")
                    .append(r.classified() == null ? "-" : r.classified()).append(typeRight(r, r.classified()) || r.expectedType() == null ? "" : " ✗")
                    .append(" | ").append(r.confidence() == null ? "-" : String.format(java.util.Locale.ROOT, "%.2f", r.confidence()))
                    .append(" | ").append(r.accepted() == null ? "-" : r.accepted())
                    .append(r.accepted() != null && !typeRight(r, r.accepted()) ? " ✗" : "").append(" | ")
                    .append(r.fieldsExpected() == 0 ? "-" : r.fieldsRight() + "/" + r.fieldsExpected())
                    .append(r.missingFields().isEmpty() && r.wrongFields().isEmpty() ? "" : " missing=" + r.missingFields().keySet() + " wrong=" + r.wrongFields())
                    .append(" | ").append(r.identifiersExpected() == 0 && r.identifiersInvented().isEmpty() ? "-" : r.identifiersFound() + "/" + r.identifiersExpected()
                            + (r.identifiersMissing().isEmpty() ? "" : " missing=" + r.identifiersMissing())
                            + (r.identifiersInvented().isEmpty() ? "" : " invented=" + r.identifiersInvented()))
                    .append(" |\n");
        }
        if (!gaps.isEmpty()) {
            out.append("\nTaxonomy gaps (expected type does not exist): ").append(gaps.stream().map(Result::expectedType).distinct().toList()).append('\n');
        }
        out.append("\nKnown types in the taxonomy: ").append(knownTypes.size()).append('\n');
        return out.toString();
    }
}
