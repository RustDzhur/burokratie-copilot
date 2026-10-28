package de.buerokratiecopilot.accounts.sector;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * What the public sector mode must not do (spec §3.1 and §35, H-006).
 *
 * <p>The acceptance criterion is that the mode contains no automatic decisions about citizens' rights,
 * and that the need for a separate legal analysis is documented. Both halves matter, and the second is not
 * a formality.</p>
 *
 * <p><strong>Why a guard and not a setting.</strong> A platform that decides whether a citizen is entitled
 * to something is a platform making an administrative act. That is a legal question, it differs by field
 * and by Land, and no configuration this software ships with can answer it. A setting could be switched on
 * by somebody who does not know that; a guard cannot.</p>
 *
 * <p><strong>What is refused.</strong> Any automation that produces a decision about a person's rights,
 * entitlements or obligations, and any automation that sends such a decision out. The platform may prepare,
 * sort, route, remind, assemble and check completeness — it does those things for a human who decides.</p>
 *
 * <p><strong>What is not refused.</strong> Everything that helps without deciding. A guard that stopped the
 * platform from sorting files would be a guard that made the software useless and would be removed within a
 * week, taking the protection with it.</p>
 *
 * <p><strong>The legal analysis is named, not implied.</strong> {@link #LEGAL_ANALYSIS_REQUIRED} states in
 * one sentence what has to happen before this mode may be used for real decisions, so that nobody can read
 * this code and conclude the question has been settled.</p>
 */
@Service
public class PublicSectorGuard {

    private static final Logger LOG = LoggerFactory.getLogger(PublicSectorGuard.class);

    /**
     * What has to be established before this mode is used for anything that binds a citizen.
     *
     * <p>Stated here in the code rather than in a document nobody opens, because the person who needs to
     * know is the person about to switch the mode on.</p>
     */
    public static final String LEGAL_ANALYSIS_REQUIRED =
            "Der Modus trifft keine automatischen Entscheidungen über Rechte von Bürgerinnen und Bürgern. "
                    + "Der Einsatz für rechtlich verbindliche Entscheidungen setzt eine gesonderte "
                    + "rechtliche Prüfung voraus, die diesen Code nicht ersetzt: welche Stelle entscheidet, "
                    + "auf welcher Grundlage, mit welcher Begründung und welchem Rechtsbehelf. Diese "
                    + "Prüfung ist zu dokumentieren, bevor der Modus dafür verwendet wird.";

    /**
     * The kinds of automation that would amount to a decision about somebody's rights.
     *
     * <p>Named individually so that the refusal can say which one was attempted, rather than "not
     * allowed", which teaches nobody anything.</p>
     */
    private static final Map<String, String> PROHIBITED = Map.of(
            "AUTOMATIC_ELIGIBILITY", "automatische Feststellung eines Anspruchs",
            "AUTOMATIC_REJECTION", "automatische Ablehnung",
            "AUTOMATIC_APPROVAL", "automatische Genehmigung",
            "AUTOMATIC_SANCTION", "automatische Sanktion",
            "AUTOMATIC_BENEFIT_CALCULATION", "automatische Leistungsberechnung",
            "AUTO_SEND_DECISION", "automatischer Versand eines Bescheids");

    /** The kinds of help that are allowed, because a person still decides. */
    private static final List<String> PERMITTED = List.of(
            "SORT", "ROUTE", "CLASSIFY", "EXTRACT_FIELDS", "CHECK_COMPLETENESS", "DETECT_DUPLICATE",
            "REMIND", "ASSEMBLE_DRAFT", "SUGGEST_CATEGORY", "MEASURE_VOLUME");

    private final JdbcTemplate jdbc;

    public PublicSectorGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The answer to a question about what the mode may do. */
    public record Verdict(boolean permitted, String automation, String reason) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("permitted", permitted);
            map.put("automation", automation);
            map.put("reason", reason);
            return map;
        }
    }

    /** Whether a tenant is in the public sector mode. */
    @Transactional(readOnly = true)
    public boolean isPublicSector(java.util.UUID tenantId) {
        return tenantId != null && "HIGH_SECURITY_ORGANIZATION".equals(accountTypeOf(tenantId));
    }

    /**
     * Whether an automation may run in this tenant.
     *
     * <p>Outside the public sector mode everything is permitted: a company deciding about its own invoices
     * is deciding about its own money, and the platform refusing that would be refusing its main job.</p>
     */
    @Transactional(readOnly = true)
    public Verdict check(java.util.UUID tenantId, String automation) {
        if (!isPublicSector(tenantId)) {
            return new Verdict(true, automation, "Der Modus trifft diese Einschränkung nicht");
        }
        String normalized = automation == null ? "" : automation.toUpperCase(java.util.Locale.ROOT);
        String prohibited = PROHIBITED.get(normalized);
        if (prohibited != null) {
            LOG.warn("Prohibited automation '{}' attempted in public sector tenant {}", normalized,
                    tenantId);
            return new Verdict(false, normalized, "Im behördlichen Modus nicht zulässig: " + prohibited
                    + ". Die Entscheidung trifft eine Person.");
        }
        return new Verdict(true, normalized, "Zulässig, weil eine Person entscheidet");
    }

    /**
     * Refuses an automation that would amount to a decision about somebody's rights.
     *
     * <p>Refused rather than warned about: a warning is something a caller can ignore, and the point is
     * that this cannot happen at all.</p>
     */
    @Transactional(readOnly = true)
    public void requirePermitted(java.util.UUID tenantId, String automation) {
        Verdict verdict = check(tenantId, automation);
        if (!verdict.permitted()) {
            throw new ApiException(ErrorCode.FORBIDDEN, verdict.reason());
        }
    }

    /** The automations that are refused, for a caller that documents them. */
    public static Set<String> prohibited() {
        return new LinkedHashSet<>(PROHIBITED.keySet());
    }

    /** The automations that are allowed, for the same reason. */
    public static List<String> permitted() {
        return PERMITTED;
    }

    /** The legal analysis this mode requires, as a value a caller can print. */
    public static String legalAnalysisRequired() {
        return LEGAL_ANALYSIS_REQUIRED;
    }

    private String accountTypeOf(java.util.UUID tenantId) {
        List<String> types = jdbc.query("""
                select account_type from tenants where id = ?
                """, (rs, row) -> rs.getString("account_type"), tenantId);
        return types.isEmpty() ? null : types.getFirst();
    }
}
