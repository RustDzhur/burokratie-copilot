package de.buerokratiecopilot.reports.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.reports.ReportService;
import de.buerokratiecopilot.retention.RetentionService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Reports, retention and legal holds (spec §22, §26; §20).
 *
 * <p>{@code ReportService} and {@code RetentionService} were implemented and tested with no endpoint, so
 * neither a figure nor a retention period could be reached by anything. This is the way in.</p>
 *
 * <p><strong>The retention periods are not this platform's.</strong> A policy carries the
 * {@code legal_basis} the customer names, and the register of deadlines is empty on purpose elsewhere in
 * this codebase for the same reason: how long a document has to be kept is a legal question that differs
 * by kind and by customer, and a platform that answered it would be inventing a rule.</p>
 *
 * <p><strong>A figure is reported with what makes it readable.</strong> A number without its period, its
 * unit and whether it is exact is a number somebody will misread, so the service attaches all three and the
 * endpoint passes them through rather than reducing the answer to a value.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reports and retention", description = "Figures, retention periods and legal holds")
public class ReportRetentionController {

    private static final String SEE_REPORTS = "hasAnyAuthority('reports.view','reports.export')";
    private static final String SEE_RETENTION = "hasAnyAuthority('settings.manage_retention','settings.view')";
    private static final String MANAGE_RETENTION = "hasAuthority('settings.manage_retention')";

    private final ReportService reports;
    private final RetentionService retention;

    public ReportRetentionController(ReportService reports, RetentionService retention) {
        this.reports = reports;
        this.retention = retention;
    }

    /** A retention policy as a caller supplies it. */
    public record PolicyBody(String code, String nameGerman, UUID documentTypeId,
                             Integer retentionPeriodMonths, String retentionTrigger, String legalBasis,
                             LocalDate validFrom) {
    }

    /** A legal hold. */
    public record HoldBody(String note) {
    }

    // ---------------------------------------------------------------- reports

    /**
     * Builds a report for a period.
     *
     * <p>The period is required rather than defaulted: a figure over an unstated period is a figure nobody
     * can interpret, and choosing one here would leave the caller believing they had chosen it.</p>
     */
    @GetMapping("/reports/{audience}")
    @PreAuthorize(SEE_REPORTS)
    @Operation(summary = "Figures for a period, each with its unit and whether it is exact")
    public Map<String, Object> report(@PathVariable String audience,
                                      @RequestParam String from, @RequestParam String to,
                                      @RequestParam(required = false) UUID workspaceId) {
        UUID tenantId = tenant();
        ReportService.Audience target;
        try {
            target = ReportService.Audience.valueOf(audience.toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Unbekannte Auswertung: " + audience + ". Möglich sind "
                            + java.util.Arrays.toString(ReportService.Audience.values()));
        }

        ReportService.Report report = reports.build(tenantId, target,
                new ReportService.ReportPeriod(LocalDate.parse(from), LocalDate.parse(to)), workspaceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audience", report.audience().name());
        body.put("period", Map.of("from", report.period().from().toString(),
                "to", report.period().to().toString(), "description", report.period().describe()));
        body.put("metrics", report.metrics().stream().map(metric -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", metric.code());
            entry.put("label", metric.label());
            entry.put("value", metric.value());
            entry.put("unit", metric.unit());
            // A figure is reported with whether it is exact, because an estimate presented as a count is
            // the kind of number somebody makes a decision on.
            entry.put("exact", metric.exact());
            return entry;
        }).toList());
        return body;
    }

    /** The figures a kind of report can contain, so an interface can offer them rather than guess. */
    @GetMapping("/reports/{audience}/metrics")
    @PreAuthorize(SEE_REPORTS)
    @Operation(summary = "The metric codes this kind of report offers")
    public Map<String, Object> metricCodes(@PathVariable String audience) {
        ReportService.Audience target;
        try {
            target = ReportService.Audience.valueOf(audience.toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Auswertung: " + audience);
        }
        return Map.of("audience", target.name(), "metricCodes", reports.metricCodes(target));
    }

    // ---------------------------------------------------------------- retention

    /**
     * Defines a retention period.
     *
     * <p>The legal basis is required: how long a document must be kept is not this platform's answer, and a
     * policy without a basis is a period somebody guessed.</p>
     */
    @PostMapping("/retention/policies")
    @PreAuthorize(MANAGE_RETENTION)
    @Operation(summary = "Defines a retention period; the legal basis is the customer's")
    public ResponseEntity<Map<String, Object>> definePolicy(@RequestBody PolicyBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body.legalBasis() == null || body.legalBasis().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Aufbewahrungsfrist braucht eine Rechtsgrundlage; die Plattform erfindet keine");
        }
        if (body.retentionPeriodMonths() == null || body.retentionPeriodMonths() <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Frist muss eine positive Anzahl Monate "
                    + "sein");
        }
        UUID policyId = retention.definePolicy(new RetentionService.PolicyRequest(tenantId, body.code(),
                body.nameGerman(), body.documentTypeId(), body.retentionPeriodMonths(),
                body.retentionTrigger(), body.legalBasis(), body.validFrom(), actor));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("policyId", policyId.toString()));
    }

    /** Confirms a policy, which is what makes it usable for deletions. */
    @PostMapping("/retention/policies/{policyId}/verify")
    @PreAuthorize(MANAGE_RETENTION)
    @Operation(summary = "Confirms a retention policy")
    public ResponseEntity<Void> verifyPolicy(@PathVariable UUID policyId) {
        retention.verifyPolicy(tenant(), policyId, CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    /** Whether a policy may be used to delete anything right now. */
    @GetMapping("/retention/policies/{policyId}/may-delete")
    @PreAuthorize(SEE_RETENTION)
    @Operation(summary = "Whether this policy may be used to delete")
    public Map<String, Object> mayDelete(@PathVariable UUID policyId) {
        return Map.of("mayDelete", retention.mayDelete(tenant(), policyId));
    }

    /**
     * Places a legal hold on a document.
     *
     * <p>A hold overrides every retention period: a document that is the subject of a dispute is not to be
     * destroyed because a date passed.</p>
     */
    @PostMapping("/documents/{documentId}/legal-hold")
    @PreAuthorize(MANAGE_RETENTION)
    @Operation(summary = "Places a legal hold, which overrides every retention period")
    public ResponseEntity<Map<String, Object>> placeHold(@PathVariable UUID documentId,
                                                         @RequestBody(required = false) HoldBody body) {
        UUID tenantId = tenant();
        retention.placeLegalHold(tenantId, documentId, CurrentUser.required(),
                body == null ? null : body.note());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("documentId", documentId.toString(), "underLegalHold", true));
    }

    /** Lifts a legal hold. */
    @PostMapping("/documents/{documentId}/legal-hold/lift")
    @PreAuthorize(MANAGE_RETENTION)
    @Operation(summary = "Lifts a legal hold")
    public ResponseEntity<Void> liftHold(@PathVariable UUID documentId,
                                         @RequestBody(required = false) HoldBody body) {
        retention.liftLegalHold(tenant(), documentId, CurrentUser.required(),
                body == null ? null : body.note());
        return ResponseEntity.noContent().build();
    }

    /** Whether a document is under a hold, which is what stops a deletion. */
    @GetMapping("/documents/{documentId}/legal-hold")
    @PreAuthorize(SEE_RETENTION)
    @Operation(summary = "Whether this document is under a legal hold")
    public Map<String, Object> hold(@PathVariable UUID documentId) {
        return Map.of("documentId", documentId.toString(),
                "underLegalHold", retention.isUnderLegalHold(tenant(), documentId));
    }

    /** Schedules a retention action for a document. */
    @PostMapping("/retention/jobs")
    @PreAuthorize(MANAGE_RETENTION)
    @Operation(summary = "Schedules a retention action; a hold or an unconfirmed policy stops it")
    public ResponseEntity<Map<String, Object>> schedule(@RequestBody Map<String, String> body) {
        UUID tenantId = tenant();
        UUID documentId = UUID.fromString(required(body, "documentId"));
        UUID policyId = UUID.fromString(required(body, "policyId"));
        CurrentUser.required();
        LocalDate scheduledFor = body.get("scheduledFor") == null ? LocalDate.now()
                : LocalDate.parse(body.get("scheduledFor"));
        UUID jobId = retention.schedule(tenantId, documentId, policyId,
                body.getOrDefault("action", "REVIEW"), scheduledFor);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("jobId", jobId.toString()));
    }

    /** Runs a scheduled action, and reports what happened rather than assuming it worked. */
    @PostMapping("/retention/jobs/{jobId}/execute")
    @PreAuthorize(MANAGE_RETENTION)
    @Operation(summary = "Runs a scheduled retention action")
    public Map<String, Object> execute(@PathVariable UUID jobId) {
        RetentionService.Outcome outcome = retention.execute(tenant(), jobId, CurrentUser.required());
        Map<String, Object> result = new LinkedHashMap<>(outcome.asMap());
        // The outcome says what happened and why, including when nothing happened: a deletion that was
        // refused by a hold is a result somebody has to be told.
        return result;
    }

    /** Serves the list of audiences, so an interface does not hard-code them. */
    @GetMapping("/reports/audiences")
    @PreAuthorize(SEE_REPORTS)
    @Operation(summary = "The kinds of report that exist")
    public List<String> audiences() {
        return java.util.Arrays.stream(ReportService.Audience.values()).map(Enum::name).toList();
    }

    private static String required(Map<String, String> body, String key) {
        String value = body == null ? null : body.get(key);
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Das Feld '" + key + "' fehlt");
        }
        return value;
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
