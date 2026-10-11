package de.buerokratiecopilot.einvoicing.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Which e-invoice validation rules are in force (spec §14, D-005c).
 *
 * <p>{@code e_invoice_records.ruleset_version} has been empty since the table was created, because
 * nothing published rule sets. This is the register that fills it, and it is deliberately a register of
 * <em>which</em> rules apply rather than an engine that knows what they mean.</p>
 *
 * <p><strong>The distinction is the whole point.</strong> The rules of EN 16931 are a legal and technical
 * specification this platform did not write and cannot verify. What a platform can honestly do is record
 * which set of rules was in force at a moment, apply the checks it implements, and say which version it
 * applied. Inventing the rules — or worse, guessing at them — would produce invoices marked valid by a
 * checklist nobody qualified has ever read.</p>
 *
 * <p><strong>The register starts empty.</strong> Publishing a rule set is an act of the operator, who
 * supplies codes, severities and descriptions, exactly as with the deadline rules. A validation that ran
 * without a published set records that it ran without one, rather than claiming a conformance it never
 * checked.</p>
 *
 * <p><strong>A published version is never edited.</strong> The same reason as with a process: an invoice
 * checked last year was checked against the rules in force then, and a register that could be rewritten
 * afterwards would make that unanswerable.</p>
 */
@Service
public class EInvoiceRuleRegister {

    private static final Logger LOG = LoggerFactory.getLogger(EInvoiceRuleRegister.class);

    /** The severities a rule can have. */
    public static final List<String> SEVERITIES = List.of("ERROR", "WARNING", "INFO");

    /** The statuses a validation can end in, as the schema defines them. */
    private static final List<String> CLEAN_STATUSES = List.of("VALID", "VALID_WITH_WARNINGS");

    private final JdbcTemplate jdbc;

    public EInvoiceRuleRegister(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One rule of a version. */
    public record Rule(String code, String severity, String description, String reference, int sortOrder) {
    }

    /** A published version. */
    public record Ruleset(UUID id, int version, String specification, String status, int ruleCount) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id.toString());
            map.put("version", version);
            map.put("specification", specification);
            map.put("status", status);
            map.put("ruleCount", ruleCount);
            return map;
        }
    }

    /** What a validation should record about the rules it used. */
    public record Applied(UUID rulesetId, Integer version, String specification, int ruleCount,
                          boolean published) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rulesetId", rulesetId == null ? null : rulesetId.toString());
            map.put("version", version);
            map.put("specification", specification);
            map.put("ruleCount", ruleCount);
            map.put("published", published);
            map.put("note", published ? null
                    : "Für diese Fassung ist kein Regelwerk veröffentlicht; die Prüfung sagt das und "
                            + "behauptet keine Konformität.");
            return map;
        }
    }

    /**
     * Publishes a version of a rule set.
     *
     * <p>A version is published once. The next change is the next version, which is what makes an old
     * validation explainable.</p>
     */
    @Transactional
    public Ruleset publish(String specification, String description, String source, List<Rule> rules,
                           UUID publishedBy) {
        String spec = normalize(specification);
        if (publishedBy == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein veröffentlichtes Regelwerk braucht eine Person, die es veröffentlicht");
        }
        if (rules == null || rules.isEmpty()) {
            // An empty rule set is not a rule set: publishing one would let a validation say "checked
            // against EN 16931" while checking nothing.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein Regelwerk ohne Regeln sagt nichts aus. Es muss mindestens eine Regel enthalten, "
                            + "sonst behauptet eine Prüfung eine Konformität, die sie nicht geprüft hat.");
        }

        List<String> codes = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.code() == null || rule.code().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Regel braucht einen Code");
            }
            if (codes.contains(rule.code())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Regelcode '" + rule.code()
                        + "' kommt mehrfach vor");
            }
            codes.add(rule.code());
            if (rule.severity() == null || !SEVERITIES.contains(rule.severity()
                    .toUpperCase(java.util.Locale.ROOT))) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Unbekannter Schweregrad: " + rule.severity());
            }
            if (rule.description() == null || rule.description().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Regel '" + rule.code()
                        + "' braucht eine Beschreibung");
            }
        }

        int version = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from e_invoice_rulesets where specification = ?
                """, Integer.class, spec);

        UUID rulesetId = jdbc.queryForObject("""
                insert into e_invoice_rulesets (id, version, specification, status, description, source,
                                                published_by, published_at)
                values (gen_random_uuid(), ?, ?, 'PUBLISHED', ?, ?, ?, now())
                returning id
                """, UUID.class, version, spec, description, source, publishedBy);

        int order = 0;
        for (Rule rule : rules) {
            jdbc.update("""
                    insert into e_invoice_rules (id, ruleset_id, code, severity, description, reference,
                                                 sort_order)
                    values (gen_random_uuid(), ?, ?, ?, ?, ?, ?)
                    """, rulesetId, rule.code().strip(),
                    rule.severity().toUpperCase(java.util.Locale.ROOT), rule.description(),
                    rule.reference(), rule.sortOrder() == 0 ? order : rule.sortOrder());
            order++;
        }

        LOG.info("Rule set {} version {} published with {} rule(s) by {}", spec, version, rules.size(),
                publishedBy);
        return new Ruleset(rulesetId, version, spec, "PUBLISHED", rules.size());
    }

    /**
     * The version in force for a specification.
     *
     * <p>Returns nothing when none is published, which is the truth rather than a default: the platform
     * does not carry a rule set of its own.</p>
     */
    @Transactional(readOnly = true)
    public Applied inForce(String specification) {
        String spec = normalize(specification);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, version from e_invoice_rulesets
                where specification = ? and status = 'PUBLISHED'
                order by version desc limit 1
                """, spec);
        if (rows.isEmpty()) {
            // The register starts empty on purpose. Saying so is the honest answer: a validation that ran
            // without a rule set must not claim a conformance it did not check.
            return new Applied(null, null, spec, 0, false);
        }
        UUID id = (UUID) rows.getFirst().get("id");
        int version = ((Number) rows.getFirst().get("version")).intValue();
        return new Applied(id, version, spec, rulesOf(id).size(), true);
    }

    /** The rules of a version. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> rulesOf(UUID rulesetId) {
        return jdbc.queryForList("""
                select code, severity, description, reference, sort_order
                from e_invoice_rules where ruleset_id = ? order by sort_order, code
                """, rulesetId);
    }

    /** The versions of a specification, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> versions(String specification) {
        return jdbc.queryForList("""
                select id, version, specification, status, description, source, published_at, retired_at
                from e_invoice_rulesets
                where (?::text is null or specification = ?)
                order by specification, version desc
                """, specification, specification);
    }

    /** Takes a version out of force without deleting it, so an old validation stays explainable. */
    @Transactional
    public void retire(UUID rulesetId, UUID retiredBy) {
        int updated = jdbc.update("""
                update e_invoice_rulesets set status = 'RETIRED', retired_at = now(), updated_at = now()
                where id = ? and status = 'PUBLISHED'
                """, rulesetId);
        if (updated == 0) {
            throw ApiException.notFound("ruleset");
        }
        LOG.info("Rule set {} retired by {}", rulesetId, retiredBy);
    }

    /**
     * Records a validation, naming the rule set that was applied.
     *
     * <p>When no set is in force the version stays empty and a clean pass is refused: a check that ran
     * against nothing must not look like a check that found nothing.</p>
     */
    @Transactional
    public Map<String, Object> recordValidation(UUID tenantId, UUID documentId, String status,
                                                Object results, String specification) {
        Applied applied = inForce(specification);
        String normalized = status == null ? "NOT_VALIDATED" : status.toUpperCase(java.util.Locale.ROOT);

        if (!applied.published() && CLEAN_STATUSES.contains(normalized)) {
            throw ApiException.conflict("Ohne veröffentlichtes Regelwerk lässt sich keine Konformität "
                    + "feststellen. Für '" + applied.specification() + "' ist keines veröffentlicht.");
        }

        int updated = jdbc.update("""
                update e_invoice_records
                set validation_status = ?, validation_results = ?::jsonb, ruleset_version = ?,
                    validated_at = now(), updated_at = now()
                where document_id = ? and tenant_id = ?
                """, normalized, de.buerokratiecopilot.workflow.WorkflowService.toJson(results),
                applied.version(), documentId, tenantId);
        if (updated == 0) {
            throw ApiException.notFound("e-invoice record");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", normalized);
        result.put("rulesetVersion", applied.version());
        result.put("rulesApplied", applied.ruleCount());
        result.put("note", applied.published() ? null
                : "Ohne veröffentlichtes Regelwerk geprüft; die Plattform erfindet keine Regeln.");
        return result;
    }

    /** The rules that were in force for a stored validation, for an invoice that has to be explained. */
    @Transactional(readOnly = true)
    public Map<String, Object> appliedTo(UUID tenantId, UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select ruleset_version, validation_status
                from e_invoice_records where document_id = ? and tenant_id = ?
                """, documentId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("e-invoice record");
        }
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        Object version = rows.getFirst().get("ruleset_version");
        if (version == null) {
            result.put("rules", List.of());
            result.put("note", "Diese Prüfung lief ohne veröffentlichtes Regelwerk.");
            return result;
        }
        List<Map<String, Object>> sets = jdbc.queryForList("""
                select id from e_invoice_rulesets where version = ? order by published_at desc limit 1
                """, Integer.parseInt(String.valueOf(version)));
        result.put("rules", sets.isEmpty() ? List.of() : rulesOf((UUID) sets.getFirst().get("id")));
        return result;
    }

    /** Whether any rule set is published at all, for a caller that reports on the installation. */
    @Transactional(readOnly = true)
    public boolean anyPublished() {
        Integer count = jdbc.queryForObject("""
                select count(*) from e_invoice_rulesets where status = 'PUBLISHED'
                """, Integer.class);
        return count != null && count > 0;
    }

    private static String normalize(String specification) {
        return specification == null || specification.isBlank() ? "EN16931"
                : specification.toUpperCase(java.util.Locale.ROOT);
    }
}
