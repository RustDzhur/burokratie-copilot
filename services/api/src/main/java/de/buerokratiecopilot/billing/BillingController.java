package de.buerokratiecopilot.billing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The customer's side of the subscription: plan, invoices, and the decision about the data after access ended.
 *
 * <p>This is the one area an owner can still reach after the access has ended (the gate in front of the API
 * lets {@code /api/v1/billing} through), because paying is the way back. Only the owner of the workspace may
 * change anything here.</p>
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final SubscriptionService subscriptions;
    private final BillingService billing;
    private final TenantPurgeService purge;
    private final TenantExportService export;
    private final JdbcTemplate jdbc;

    public BillingController(SubscriptionService subscriptions, BillingService billing, TenantPurgeService purge,
                             TenantExportService export, JdbcTemplate jdbc) {
        this.export = export;
        this.subscriptions = subscriptions;
        this.billing = billing;
        this.purge = purge;
        this.jdbc = jdbc;
    }

    public record PlanBody(String planCode) {
    }

    public record InvoiceBody(String method, Integer months, String customerName, String customerAddress,
                              String customerCountry, String customerVatId) {
    }

    public record DataChoiceBody(String choice, String confirm) {
    }

    @GetMapping("/subscription")
    public Map<String, Object> subscription() {
        UUID tenantId = tenant();
        Map<String, Object> result = new LinkedHashMap<>(subscriptions.view(tenantId));
        result.put("owner", isOwner(tenantId));
        result.put("invoiceAvailable", billing.invoiceConfigured());
        result.put("cryptoAvailable", billing.cryptoConfigured());
        result.put("plans", plans());
        return result;
    }

    @PostMapping("/plan")
    public Map<String, Object> changePlan(@RequestBody PlanBody body) {
        UUID tenantId = tenant();
        requireOwner(tenantId);
        subscriptions.changePlan(tenantId, body.planCode());
        return subscription();
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices() {
        UUID tenantId = tenant();
        requireOwner(tenantId);
        return billing.list(tenantId);
    }

    @PostMapping("/invoices")
    public ResponseEntity<Map<String, Object>> createInvoice(@RequestBody InvoiceBody body) {
        UUID tenantId = tenant();
        requireOwner(tenantId);
        String email = jdbc.queryForObject("select email::text from users where id = ?", String.class,
                CurrentUser.required());
        Map<String, Object> invoice = billing.create(tenantId, email, new BillingService.InvoiceRequest(
                body.method(), body.months(), body.customerName(), body.customerAddress(), body.customerCountry(),
                body.customerVatId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(invoice);
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        UUID tenantId = tenant();
        requireOwner(tenantId);
        Map<String, Object> invoice = billing.get(tenantId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + invoice.get("invoice_number") + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(billing.pdf(tenantId, id));
    }

    /**
     * What the owner decides after the access ended. KEEP records the wish to keep the data until the retention
     * date; DELETE removes everything at once and needs the word DELETE as confirmation, because nothing brings
     * it back.
     */
    @PostMapping("/data-choice")
    public Map<String, Object> dataChoice(@RequestBody DataChoiceBody body) {
        UUID tenantId = tenant();
        requireOwner(tenantId);
        String choice = body.choice() == null ? "" : body.choice().toUpperCase(java.util.Locale.ROOT);
        if ("DELETE".equals(choice)) {
            if (!"DELETE".equals(body.confirm())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Zum Löschen muss DELETE bestätigt werden");
            }
            // Erasure is the owner's right at any time, not only after the access ended.
            TenantPurgeService.Result result = RequestContext.runAs((UUID) null, () -> purge.purge(tenantId));
            return Map.of("deleted", true, "files", result.files());
        }
        subscriptions.recordDataChoice(tenantId, choice);
        return Map.of("deleted", false, "choice", choice);
    }

    /**
     * All the customer's data as one ZIP; reachable after the access ended, so the data can be taken along.
     *
     * <p>Written straight to the response on the request thread, not through an asynchronous streaming body: the
     * asynchronous dispatch passes the security filters again without the session and would be refused after the first
     * bytes had been sent.</p>
     */
    @GetMapping("/export")
    public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        UUID tenantId = tenant();
        requireOwner(tenantId);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"buerokratie-copilot-export.zip\"");
        export.write(tenantId, response.getOutputStream());
    }

    private List<Map<String, Object>> plans() {
        return jdbc.queryForList("select code, name, price_cents, currency, tier, audiences, analyses_per_month, features from plans where is_active order by sort_order");
    }

    private boolean isOwner(UUID tenantId) {
        Boolean owner = jdbc.queryForObject("""
                select coalesce(bool_or(is_owner), false) from memberships where tenant_id = ? and user_id = ?
                  and status = 'ACTIVE'
                """, Boolean.class, tenantId, CurrentUser.required());
        return Boolean.TRUE.equals(owner);
    }

    private void requireOwner(UUID tenantId) {
        if (!isOwner(tenantId)) {
            throw ApiException.forbidden("Nur der Inhaber des Arbeitsbereichs kann den Tarif verwalten");
        }
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
