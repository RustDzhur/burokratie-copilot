package de.buerokratiecopilot.einvoicing.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies the register of e-invoice rule sets (spec §14, D-005c).
 *
 * <p>What is checked is not that a rule set is correct — this platform cannot say that, and pretending to
 * would be the whole problem. What is checked is that the register is honest: it starts empty, a published
 * version is never edited, an empty rule set is refused because it would let a validation claim a
 * conformance it never checked, and a validation that ran without a published set says so instead of
 * producing a clean pass.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class EInvoiceRuleRegisterIT {

    @Autowired
    private EInvoiceRuleRegister register;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * A specification nobody else uses, so the tests do not see each other's published versions.
     *
     * <p>Versions are numbered per specification and the test database survives between runs, so a fixed
     * name would make the expected version number depend on how often the suite has been run.</p>
     */
    private String specification() {
        return "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Regelpfleger', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "er-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private EInvoiceRuleRegister.Rule rule(String code, String severity) {
        return new EInvoiceRuleRegister.Rule(code, severity, "Prüft " + code, null, 0);
    }

    private List<EInvoiceRuleRegister.Rule> twoRules() {
        return List.of(rule("BR-01", "ERROR"), rule("BR-02", "WARNING"));
    }

    /** An e-invoice record to validate against. */
    private record Record(UUID tenantId, UUID documentId) {
    }

    private Record aRecord() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'E-Rechnung Test', 'COMPANY') returning id
                """, UUID.class, "er-" + UUID.randomUUID().toString().substring(0, 12));
        UUID documentId = jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'rechnung.xml', 'rechnung.xml', 'application/xml', 10, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED')
                returning id
                """, UUID.class, tenantId,
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        jdbc.update("""
                insert into e_invoice_records (id, tenant_id, document_id, format, validation_status)
                values (gen_random_uuid(), ?, ?, 'XRECHNUNG', 'NOT_VALIDATED')
                """, tenantId, documentId);
        return new Record(tenantId, documentId);
    }

    // ---------------------------------------------------------------- the register starts empty

    @Test
    @DisplayName("the register carries no rules of its own until somebody publishes one")
    void theRegisterStartsEmpty() {
        String spec = specification();

        EInvoiceRuleRegister.Applied applied = register.inForce(spec);

        assertThat(applied.published()).isFalse();
        assertThat(applied.version()).isNull();
        assertThat(applied.ruleCount()).isZero();
        assertThat(String.valueOf(applied.asMap().get("note")))
                .as("the platform does not carry a rule set of its own, and says so")
                .contains("kein Regelwerk");
    }

    @Test
    @DisplayName("a clean pass is refused while no rule set is published")
    void aCleanPassNeedsAPublishedRuleset() {
        Record record = aRecord();
        String spec = specification();

        // A check that ran against nothing must not look like a check that found nothing.
        assertThatThrownBy(() -> RequestContext.runAs(record.tenantId(),
                () -> register.recordValidation(record.tenantId(), record.documentId(), "VALID", Map.of(),
                        spec)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("keine Konformität");
    }

    @Test
    @DisplayName("a validation without a rule set may still record that nothing was validated")
    void withoutARulesetNothingIsClaimed() {
        Record record = aRecord();

        Map<String, Object> result = RequestContext.runAs(record.tenantId(),
                () -> register.recordValidation(record.tenantId(), record.documentId(), "NOT_VALIDATED",
                        Map.of("reason", "kein Regelwerk"), specification()));

        assertThat(result).containsEntry("status", "NOT_VALIDATED");
        assertThat(result.get("rulesetVersion")).isNull();
        assertThat(String.valueOf(result.get("note"))).contains("erfindet keine Regeln");
    }

    // ---------------------------------------------------------------- publishing

    @Test
    @DisplayName("publishing a rule set records the version and the rules")
    void publishingRecordsTheVersion() {
        String spec = specification();

        EInvoiceRuleRegister.Ruleset published = RequestContext.runAs(null,
                () -> register.publish(spec, "Erste Fassung", "EN 16931, Abschnitt 6", twoRules(),
                        person()));

        assertThat(published.version()).isEqualTo(1);
        assertThat(published.ruleCount()).isEqualTo(2);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(RequestContext.runAs(null, () -> register.rulesOf(published.id())))
                .hasSize(2)
                .extracting(row -> row.get("code")).containsExactly("BR-01", "BR-02");
    }

    @Test
    @DisplayName("the next publication is the next version, not an edit of the first")
    void theNextPublicationIsTheNextVersion() {
        String spec = specification();
        EInvoiceRuleRegister.Ruleset first = RequestContext.runAs(null,
                () -> register.publish(spec, "Erste", null, twoRules(), person()));

        EInvoiceRuleRegister.Ruleset second = RequestContext.runAs(null, () -> register.publish(spec,
                "Zweite", null, List.of(rule("BR-01", "ERROR"), rule("BR-03", "INFO")), person()));

        assertThat(second.version()).isEqualTo(2);
        // An invoice checked last year was checked against the rules in force then.
        assertThat(RequestContext.runAs(null, () -> register.rulesOf(first.id())))
                .extracting(row -> row.get("code")).containsExactly("BR-01", "BR-02");
        assertThat(register.inForce(spec).version()).isEqualTo(2);
        assertThat(RequestContext.runAs(null, () -> register.rulesOf(second.id())))
                .extracting(row -> row.get("code")).containsExactly("BR-01", "BR-03");
    }

    @Test
    @DisplayName("an empty rule set is refused, because it would let a validation claim a conformance")
    void anEmptyRulesetIsRefused() {
        assertThatThrownBy(() -> RequestContext.runAs(null,
                () -> register.publish(specification(), null, null, List.of(), person())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ohne Regeln");
    }

    @Test
    @DisplayName("a rule without a code, without a description or with an unknown severity is refused")
    void theRulesAreChecked() {
        assertThatThrownBy(() -> RequestContext.runAs(null, () -> register.publish(specification(), null,
                null, List.of(new EInvoiceRuleRegister.Rule(null, "ERROR", "x", null, 0)), person())))
                .isInstanceOf(ApiException.class).hasMessageContaining("Code");
        assertThatThrownBy(() -> RequestContext.runAs(null, () -> register.publish(specification(), null,
                null, List.of(new EInvoiceRuleRegister.Rule("BR-01", "CATASTROPHIC", "x", null, 0)),
                person())))
                .isInstanceOf(ApiException.class).hasMessageContaining("Schweregrad");
        assertThatThrownBy(() -> RequestContext.runAs(null, () -> register.publish(specification(), null,
                null, List.of(new EInvoiceRuleRegister.Rule("BR-01", "ERROR", "  ", null, 0)), person())))
                .isInstanceOf(ApiException.class).hasMessageContaining("Beschreibung");
    }

    @Test
    @DisplayName("a rule code may not appear twice in one version")
    void aDuplicateCodeIsRefused() {
        assertThatThrownBy(() -> RequestContext.runAs(null, () -> register.publish(specification(), null,
                null, List.of(rule("BR-01", "ERROR"), rule("BR-01", "WARNING")), person())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("mehrfach");
    }

    @Test
    @DisplayName("a publication needs a person, so that a version can be attributed")
    void aPublicationNeedsAPerson() {
        assertThatThrownBy(() -> RequestContext.runAs(null,
                () -> register.publish(specification(), null, null, twoRules(), null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Person");
    }

    // ---------------------------------------------------------------- using it

    @Test
    @DisplayName("a validation records the version of the rules that were in force")
    void aValidationRecordsTheVersion() {
        Record record = aRecord();
        String spec = specification();
        RequestContext.runAs(null, () -> register.publish(spec, "Erste", null, twoRules(), person()));

        Map<String, Object> result = RequestContext.runAs(record.tenantId(),
                () -> register.recordValidation(record.tenantId(), record.documentId(), "VALID",
                        Map.of("checked", 2), spec));

        assertThat(result).containsEntry("rulesetVersion", 1);
        assertThat(result).containsEntry("rulesApplied", 2);
        assertThat(jdbc.queryForObject("""
                select ruleset_version from e_invoice_records where document_id = ?
                """, Integer.class, record.documentId()))
                .as("the column that had been empty since the table was created")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("what was in force for a stored validation can be read back afterwards")
    void whatWasInForceCanBeReadBack() {
        Record record = aRecord();
        String spec = specification();
        RequestContext.runAs(null, () -> register.publish(spec, "Erste", null, twoRules(), person()));
        RequestContext.runAs(record.tenantId(), () -> register.recordValidation(record.tenantId(),
                record.documentId(), "INVALID", Map.of("failed", List.of("BR-01")), spec));

        Map<String, Object> applied = RequestContext.runAs(record.tenantId(),
                () -> register.appliedTo(record.tenantId(), record.documentId()));

        assertThat(applied).containsEntry("validation_status", "INVALID");
        assertThat((List<?>) applied.get("rules")).hasSize(2);
    }

    @Test
    @DisplayName("a validation that ran without a rule set says so when it is read back")
    void aValidationWithoutARulesetSaysSo() {
        Record record = aRecord();
        RequestContext.runAs(record.tenantId(), () -> register.recordValidation(record.tenantId(),
                record.documentId(), "NOT_VALIDATED", Map.of(), specification()));

        Map<String, Object> applied = RequestContext.runAs(record.tenantId(),
                () -> register.appliedTo(record.tenantId(), record.documentId()));

        assertThat((List<?>) applied.get("rules")).isEmpty();
        assertThat(String.valueOf(applied.get("note"))).contains("ohne veröffentlichtes Regelwerk");
    }

    @Test
    @DisplayName("retiring a version keeps it, so an old validation stays explainable")
    void retiringKeepsTheVersion() {
        String spec = specification();
        EInvoiceRuleRegister.Ruleset published = RequestContext.runAs(null,
                () -> register.publish(spec, "Erste", null, twoRules(), person()));

        RequestContext.runAs(null, () -> {
            register.retire(published.id(), person());
            return null;
        });

        assertThat(register.inForce(spec).published())
                .as("a retired version is not in force")
                .isFalse();
        assertThat(RequestContext.runAs(null, () -> register.versions(spec)))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("RETIRED"));
    }

    @Test
    @DisplayName("retiring an unknown version is reported")
    void retiringAnUnknownVersionIsReported() {
        assertThatThrownBy(() -> RequestContext.runAs(null,
                () -> register.retire(UUID.randomUUID(), person())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("validating a document that has no e-invoice record is reported")
    void validatingWithoutARecordIsReported() {
        assertThatThrownBy(() -> RequestContext.runAs(null,
                () -> register.recordValidation(UUID.randomUUID(), UUID.randomUUID(), "INVALID", Map.of(),
                        specification())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("each specification has its own versions")
    void eachSpecificationHasItsOwnVersions() {
        String first = specification();
        String second = specification();
        RequestContext.runAs(null, () -> register.publish(first, null, null, twoRules(), person()));
        RequestContext.runAs(null, () -> register.publish(second, null, null, twoRules(), person()));

        // Both are version one, because they are different rule sets and not two editions of one.
        assertThat(register.inForce(first).version()).isEqualTo(1);
        assertThat(register.inForce(second).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("the severities offered are the ones the schema allows")
    void theSeveritiesAreTheSchemaValues() {
        assertThat(EInvoiceRuleRegister.SEVERITIES).containsExactlyInAnyOrder("ERROR", "WARNING", "INFO");
    }
}
