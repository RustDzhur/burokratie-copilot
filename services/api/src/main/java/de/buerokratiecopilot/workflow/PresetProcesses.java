package de.buerokratiecopilot.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * The processes the specification requires to exist (spec §16.2, F-005).
 *
 * <p>They are definitions like any other — data, published as versions, run by the same engine — and that
 * is the point. A preset written into the engine would be a process a customer could not change, and the
 * first thing any customer wants to change is where the approval threshold sits.</p>
 *
 * <p>What each one contains is what the specification names; nothing is added to them that the
 * specification does not ask for. In particular no deadline and no threshold is invented here: the steps
 * that need one read it from the rule the customer configured, and a process that has no rule behind it
 * stops where a person has to decide rather than guessing.</p>
 */
@Service
public class PresetProcesses {

    private static final Logger LOG = LoggerFactory.getLogger(PresetProcesses.class);

    private final JdbcTemplate jdbc;
    private final WorkflowService workflows;

    public PresetProcesses(JdbcTemplate jdbc, WorkflowService workflows) {
        this.jdbc = jdbc;
        this.workflows = workflows;
    }

    /** One preset, with its code and definition. */
    public record Preset(String code, String name, Map<String, Object> definition) {
    }

    /**
     * «Обработка входящего счёта» — an incoming invoice.
     *
     * <p>Receive, check the requisites, get an approval, prepare the payment. The approval step is where
     * the process stops when the amount needs one; whether it does is the customer's rule, not this
     * class's.</p>
     */
    public static Preset incomingInvoice() {
        return new Preset("incoming_invoice", "Eingangsrechnung bearbeiten", Map.of(
                "entryStep", "receive",
                "steps", List.of(
                        step("receive", "ACTION", Map.of("next", "check")),
                        step("check", "ACTION", Map.of("next", "needs_approval")),
                        step("needs_approval", "CONDITION",
                                Map.of("field", "requiresApproval", "equals", "true",
                                        "onTrue", "approve", "onFalse", "publish")),
                        step("approve", "APPROVAL", Map.of("next", "publish")),
                        step("publish", "ACTION", Map.of("next", "payment_task")),
                        step("payment_task", "TASK_CREATE", Map.of("next", "end")),
                        step("end", "TERMINATE", Map.of()))));
    }

    /** «Письмо от ведомства» — a letter from an authority. */
    public static Preset authorityLetter() {
        return new Preset("authority_letter", "Schreiben einer Behörde bearbeiten", Map.of(
                "entryStep", "receive",
                "steps", List.of(
                        step("receive", "ACTION", Map.of("next", "deadline")),
                        step("deadline", "ACTION", Map.of("next", "has_deadline")),
                        step("has_deadline", "CONDITION",
                                Map.of("field", "hasDeadline", "equals", "true",
                                        "onTrue", "assign", "onFalse", "assign")),
                        step("assign", "TASK_CREATE", Map.of("next", "wait_reply")),
                        step("wait_reply", "WAIT",
                                Map.of("waitFor", "response.drafted", "next", "end")),
                        step("end", "TERMINATE", Map.of()))));
    }

    /** «Контроль договора» — reviewing a contract. */
    public static Preset contractReview() {
        return new Preset("contract_review", "Vertrag prüfen", Map.of(
                "entryStep", "receive",
                "steps", List.of(
                        step("receive", "ACTION", Map.of("next", "terms")),
                        step("terms", "ACTION", Map.of("next", "notice_period")),
                        step("notice_period", "CONDITION",
                                Map.of("field", "hasNoticePeriod", "equals", "true",
                                        "onTrue", "remind", "onFalse", "approve")),
                        step("remind", "TASK_CREATE", Map.of("next", "approve")),
                        step("approve", "APPROVAL", Map.of("next", "end")),
                        step("end", "TERMINATE", Map.of()))));
    }

    /** All three, for a caller that installs them. */
    public static List<Preset> all() {
        return List.of(incomingInvoice(), authorityLetter(), contractReview());
    }

    /**
     * Installs the presets for a tenant.
     *
     * <p>Idempotent: a preset that is already there is left alone, including its published versions. An
     * installation that ran twice must not publish a second version of a process the customer has since
     * edited.</p>
     */
    @Transactional
    public Map<String, Object> install(UUID tenantId, UUID installedBy) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> installed = new ArrayList<>();
        List<String> alreadyThere = new ArrayList<>();

        for (Preset preset : all()) {
            List<UUID> existing = jdbc.query("""
                    select id from workflow_definitions where tenant_id = ? and code = ?
                    """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, preset.code());
            if (!existing.isEmpty()) {
                alreadyThere.add(preset.code());
                continue;
            }
            WorkflowService.Workflow workflow = workflows.define(tenantId, preset.code(), preset.name(),
                    "Vorlage aus der Spezifikation", "MANUAL", Map.of(), installedBy);
            workflows.publish(tenantId, workflow.definitionId(), preset.definition(), Map.of(),
                    "Vorlage aus der Spezifikation", installedBy);
            installed.add(preset.code());
        }

        result.put("installed", installed);
        result.put("alreadyPresent", alreadyThere);
        LOG.info("Presets installed for tenant {}: {} new", tenantId, installed.size());
        return result;
    }

    /** The preset behind a code, for a caller that wants to look before installing. */
    public static Preset byCode(String code) {
        return all().stream().filter(preset -> preset.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.notFound("preset"));
    }

    private static Map<String, Object> step(String key, String type, Map<String, Object> config) {
        return Map.of("key", key, "type", type, "config", config);
    }
}
