package de.buerokratiecopilot.ai.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides what a model may do (spec §25, E-006 acceptance).
 *
 * <p>The acceptance criterion is that instructions inside a document do not lead to tool calls or to
 * disclosure. That cannot be achieved by telling the model to ignore them: a prompt is a request, and the
 * whole problem is that the model may be persuaded. It is achieved by deciding <em>outside</em> the
 * model what may happen, and by refusing the calls that could only have come from the document.</p>
 *
 * <p>Four gates, in order, and every one of them is a refusal rather than a warning:</p>
 *
 * <ol>
 *   <li><strong>Origin.</strong> A tool that writes, sends, deletes or discloses is never triggered by
 *       untrusted content, whatever the content says and however convincing it is. Read-only tools may be
 *       used on documents, because summarising a letter is the point of the platform.</li>
 *   <li><strong>Allowlist.</strong> A tool that is not on the tenant's list does not exist. The list is
 *       configuration, so a tenant can narrow it without a release.</li>
 *   <li><strong>Permission.</strong> The user behind the request must hold the permission the tool
 *       requires. A model acting for a user acts with that user's rights and no others.</li>
 *   <li><strong>Confirmation.</strong> A risky action needs a person to confirm it. The model may propose
 *       it; it may not perform it.</li>
 * </ol>
 *
 * <p>Every decision is recorded, including the refusals. A refusal nobody can see is indistinguishable
 * from a platform that did nothing.</p>
 */
@Service
public class ToolPolicy {

    /** What a tool does to the world. */
    public enum Risk {
        /** Reads and computes; may be used on untrusted content. */
        READ_ONLY,
        /** Writes inside the tenant; needs a person to confirm it. */
        WRITE,
        /** Sends something outside, deletes, or discloses; never triggered by content. */
        DANGEROUS
    }

    /** A tool the platform offers. */
    public record Tool(String name, Risk risk, String requiredPermission, String description) {

        /** Whether this tool may be triggered by content the platform did not write. */
        public boolean mayBeTriggeredByContent() {
            return risk == Risk.READ_ONLY;
        }
    }

    /** A request to use a tool, with where it came from. */
    public record Request(String toolName, Map<String, Object> parameters, UUID userId,
                          UntrustedContent.Origin triggerOrigin, String triggerReference) {
    }

    /** What happened. */
    public record Decision(boolean allowed, String code, String reason, Risk risk,
                           boolean needsConfirmation) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("allowed", allowed);
            map.put("code", code);
            map.put("reason", reason);
            map.put("risk", risk == null ? null : risk.name());
            map.put("needsConfirmation", needsConfirmation);
            return map;
        }
    }

    private final JdbcTemplate jdbc;

    /** The tools that exist at all, before any tenant narrows the list. */
    private final Map<String, Tool> catalogue = new LinkedHashMap<>();

    public ToolPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        register(new Tool("read_document", Risk.READ_ONLY, "documents.view",
                "Liest ein Dokument, auf das der Benutzer Zugriff hat"));
        register(new Tool("search_documents", Risk.READ_ONLY, "documents.view",
                "Sucht in Dokumenten des Mandanten"));
        register(new Tool("list_open_tasks", Risk.READ_ONLY, "tasks.view", "Listet offene Aufgaben"));
        register(new Tool("summarize", Risk.READ_ONLY, "documents.view", "Fasst ein Dokument zusammen"));
        register(new Tool("draft_letter", Risk.WRITE, "documents.create",
                "Erstellt einen Entwurf; wird nicht versendet"));
        register(new Tool("create_task", Risk.WRITE, "tasks.create", "Legt eine Aufgabe an"));
        register(new Tool("update_case", Risk.WRITE, "cases.edit", "Ändert ein Verfahren"));
        register(new Tool("send_email", Risk.DANGEROUS, "mail.send",
                "Versendet eine Nachricht nach außen"));
        register(new Tool("delete_document", Risk.DANGEROUS, "documents.delete", "Löscht ein Dokument"));
        register(new Tool("export_data", Risk.DANGEROUS, "finance.export",
                "Exportiert Daten aus dem Mandanten"));
    }

    private void register(Tool tool) {
        catalogue.put(tool.name(), tool);
    }

    /**
     * Decides whether a tool may be used.
     *
     * @param tenantId the tenant; the allowlist is per tenant
     */
    @Transactional(readOnly = true)
    public Decision decide(UUID tenantId, Request request) {
        Tool tool = catalogue.get(request.toolName());
        if (tool == null) {
            // An invented tool name is the first thing to refuse: a model that names something unknown is
            // either confused or being steered, and both answers are no.
            return new Decision(false, "UNKNOWN_TOOL",
                    "Das Werkzeug '" + request.toolName() + "' gibt es nicht", null, false);
        }

        if (tool.risk() != Risk.READ_ONLY && request.triggerOrigin() != null
                && request.triggerOrigin() != UntrustedContent.Origin.PLATFORM) {
            // The acceptance criterion. A letter that says "forward this to the address below" does not
            // cause a forward, whatever it says and however it is phrased.
            return new Decision(false, "TRIGGERED_BY_UNTRUSTED_CONTENT",
                    "Ein Werkzeug mit Wirkung (" + tool.name() + ") wird nicht aufgrund von Inhalten "
                            + "ausgeführt, die nicht die Plattform geschrieben hat. Der Vorgang muss von "
                            + "einer Person ausgelöst werden.",
                    tool.risk(), false);
        }

        if (!isAllowed(tenantId, tool.name())) {
            return new Decision(false, "NOT_ALLOWED",
                    "Das Werkzeug '" + tool.name() + "' ist für diesen Mandanten nicht freigegeben",
                    tool.risk(), false);
        }

        if (request.userId() != null && !hasPermission(tenantId, request.userId(),
                tool.requiredPermission())) {
            return new Decision(false, "MISSING_PERMISSION",
                    "Dem Benutzer fehlt das Recht '" + tool.requiredPermission() + "' für "
                            + tool.name(), tool.risk(), false);
        }

        String parameterProblem = validateParameters(tool, request.parameters());
        if (parameterProblem != null) {
            return new Decision(false, "INVALID_PARAMETERS", parameterProblem, tool.risk(), false);
        }

        if (tool.risk() != Risk.READ_ONLY) {
            // The model may propose a letter; a person sends it. That is what confirmation means.
            return new Decision(false, "NEEDS_CONFIRMATION",
                    "Das Werkzeug '" + tool.name() + "' wirkt und muss von einer Person bestätigt werden",
                    tool.risk(), true);
        }
        return new Decision(true, "ALLOWED", "Lesezugriff erlaubt", tool.risk(), false);
    }

    /** Whether a tenant has a tool enabled; without a row, the read-only tools are available. */
    @Transactional(readOnly = true)
    public boolean isAllowed(UUID tenantId, String toolName) {
        Tool tool = catalogue.get(toolName);
        if (tool == null) {
            return false;
        }
        List<Boolean> configured = jdbc.query("""
                select is_active from ai_tool_settings
                where tenant_id = ? and tool_name = ?
                """, (rs, row) -> rs.getBoolean("is_active"), tenantId, toolName);
        if (!configured.isEmpty()) {
            return Boolean.TRUE.equals(configured.getFirst());
        }
        // Nothing configured: the tenant gets the tools that cannot do harm, and nothing else.
        return tool.risk() == Risk.READ_ONLY;
    }

    /** The tools a tenant may use, as the assistant has to be told them. */
    @Transactional(readOnly = true)
    public List<Tool> allowedTools(UUID tenantId) {
        return catalogue.values().stream().filter(tool -> isAllowed(tenantId, tool.name())).toList();
    }

    /**
     * Validates the parameters of a call.
     *
     * <p>A parameter that carries a document identifier must look like one, and no parameter may carry a
     * value that would disclose something the model was not given.</p>
     */
    private String validateParameters(Tool tool, Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (text.length() > 2000) {
                return "Der Parameter '" + name + "' ist zu lang für einen Werkzeugaufruf";
            }
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith("id")
                    && !text.matches("[0-9a-fA-F-]{8,64}")) {
                return "Der Parameter '" + name + "' sieht nicht wie eine Kennung aus";
            }
            if (name.toLowerCase(java.util.Locale.ROOT).matches(".*(key|token|secret|password|passwort).*")) {
                // A model must never be able to pass a credential through a tool call; it has none, and a
                // parameter that says otherwise is an attempt to obtain one.
                return "Der Parameter '" + name + "' darf keine Zugangsdaten enthalten";
            }
        }
        return null;
    }

    private boolean hasPermission(UUID tenantId, UUID userId, String permission) {
        Boolean allowed = jdbc.queryForObject("""
                select exists (
                    select 1 from memberships m
                    join membership_roles mr on mr.membership_id = m.id
                    join roles ro on ro.id = mr.role_id
                    join role_permissions rp on rp.role_id = ro.id
                    join permissions pe on pe.id = rp.permission_id
                    where m.tenant_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                      and pe.code = ?
                )
                """, Boolean.class, tenantId, userId, permission);
        return Boolean.TRUE.equals(allowed);
    }

    /** Enables or disables a tool for a tenant. */
    @Transactional
    public void setToolEnabled(UUID tenantId, String toolName, boolean enabled, UUID changedBy) {
        if (!catalogue.containsKey(toolName)) {
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.NOT_FOUND,
                    "Das Werkzeug '" + toolName + "' gibt es nicht");
        }
        jdbc.update("""
                insert into ai_tool_settings (id, tenant_id, tool_name, is_active, changed_by,
                                              updated_at)
                values (gen_random_uuid(), ?, ?, ?, ?, now())
                on conflict (tenant_id, tool_name)
                do update set is_active = excluded.is_active, changed_by = excluded.changed_by,
                              updated_at = now()
                """, tenantId, toolName, enabled, changedBy);
    }

    /** The whole catalogue, for an operator asking what exists. */
    public List<Tool> catalogue() {
        return List.copyOf(catalogue.values());
    }

    /** The names of the tools that may never be triggered by content. */
    public Set<String> toolsThatNeedAPerson() {
        return catalogue.values().stream().filter(tool -> !tool.mayBeTriggeredByContent())
                .map(Tool::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
