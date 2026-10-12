package de.buerokratiecopilot.billing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The platform administrator's area: all customers, plans and prices, payments, the platform's own settings and
 * the status of the services that serve every customer.
 *
 * <p>Reachable only with the {@code platform.admin} authority, which is given to exactly the verified addresses
 * listed in {@code BC_PLATFORM_ADMINS}. Every call runs in the system context, because it looks across tenants:
 * {@link #asSystem} does that, <em>before</em> any transaction starts, so the connection is borrowed without a
 * tenant.</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('" + PlatformAdminResolver.AUTHORITY + "')")
public class PlatformAdminController {

    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;
    private final BillingService billing;
    private final PlatformSettings settings;
    private final TenantPurgeService purge;
    private final SubscriptionLifecycleJob lifecycle;
    private final BurokratieProperties properties;

    public PlatformAdminController(JdbcTemplate jdbc, SubscriptionService subscriptions, BillingService billing,
                                   PlatformSettings settings, TenantPurgeService purge,
                                   SubscriptionLifecycleJob lifecycle, BurokratieProperties properties) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.billing = billing;
        this.settings = settings;
        this.purge = purge;
        this.lifecycle = lifecycle;
        this.properties = properties;
    }

    public record MonthsBody(Integer months) {
    }

    public record DaysBody(Integer days) {
    }

    public record PlanBody(String planCode) {
    }

    public record PlanEdit(String name, Integer priceCents, Boolean active) {
    }

    public record SettingBody(String key, Object value) {
    }

    private static <T> T asSystem(Supplier<T> action) {
        return RequestContext.runAs((UUID) null, action);
    }

    // ------------------------------------------------------------------------------------------ overview

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return asSystem(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tenants", jdbc.queryForObject("select count(*) from subscriptions", Long.class));
            result.put("byStatus", jdbc.queryForList(
                    "select status, count(*) as count from subscriptions group by status order by status"));
            result.put("monthlyRecurringCents", jdbc.queryForObject("""
                    select coalesce(sum(p.price_cents), 0) from subscriptions s join plans p on p.code = s.plan_code
                    where s.status = 'ACTIVE' and s.access_until >= now()
                    """, Long.class));
            result.put("trialEndingSoon", jdbc.queryForObject("""
                    select count(*) from subscriptions where status = 'TRIAL' and access_until between now()
                      and now() + interval '7 days'
                    """, Long.class));
            result.put("openInvoices", jdbc.queryForObject(
                    "select count(*) from billing_invoices where status = 'ISSUED'", Long.class));
            result.put("payeeConfigured", billing.invoiceConfigured());
            result.put("cryptoConfigured", billing.cryptoConfigured());
            return result;
        });
    }

    // ------------------------------------------------------------------------------------------- tenants

    @GetMapping("/tenants")
    public List<Map<String, Object>> tenants(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) String status) {
        return asSystem(() -> {
            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    select t.id, t.name, t.account_type, t.created_at, s.plan_code, s.status, s.access_until,
                           s.retention_until, s.data_choice,
                           (select u.email::text from memberships m join users u on u.id = m.user_id
                             where m.tenant_id = t.id and m.is_owner order by m.created_at limit 1) as owner_email,
                           (select count(*) from memberships m where m.tenant_id = t.id and m.status = 'ACTIVE')
                               as members
                    from tenants t join subscriptions s on s.tenant_id = t.id where t.deleted_at is null
                    """);
            if (q != null && !q.isBlank()) {
                sql.append(" and (t.name ilike ? or exists (select 1 from memberships m join users u on u.id = m.user_id"
                        + " where m.tenant_id = t.id and u.email::text ilike ?))");
                args.add("%" + q.trim() + "%");
                args.add("%" + q.trim() + "%");
            }
            if (status != null && !status.isBlank()) {
                sql.append(" and s.status = ?");
                args.add(status.toUpperCase(java.util.Locale.ROOT));
            }
            sql.append(" order by t.created_at desc limit 500");
            return jdbc.queryForList(sql.toString(), args.toArray());
        });
    }

    @PostMapping("/tenants/{id}/extend")
    public Map<String, Object> extend(@PathVariable UUID id, @RequestBody MonthsBody body) {
        int months = body.months() == null ? 1 : body.months();
        if (months < 1 || months > 36) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Laufzeit muss zwischen 1 und 36 Monaten liegen");
        }
        return asSystem(() -> {
            subscriptions.extend(id, months);
            return subscriptions.view(id);
        });
    }

    @PostMapping("/tenants/{id}/grant-days")
    public Map<String, Object> grantDays(@PathVariable UUID id, @RequestBody DaysBody body) {
        int days = body.days() == null ? 0 : body.days();
        if (days < 1 || days > 365) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Tage müssen zwischen 1 und 365 liegen");
        }
        return asSystem(() -> {
            subscriptions.grantDays(id, days);
            return subscriptions.view(id);
        });
    }

    @PostMapping("/tenants/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable UUID id) {
        return asSystem(() -> {
            subscriptions.setSuspended(id, true);
            return subscriptions.view(id);
        });
    }

    @PostMapping("/tenants/{id}/unsuspend")
    public Map<String, Object> unsuspend(@PathVariable UUID id) {
        return asSystem(() -> {
            subscriptions.setSuspended(id, false);
            return subscriptions.view(id);
        });
    }

    @PostMapping("/tenants/{id}/plan")
    public Map<String, Object> plan(@PathVariable UUID id, @RequestBody PlanBody body) {
        return asSystem(() -> {
            subscriptions.changePlan(id, body.planCode());
            return subscriptions.view(id);
        });
    }

    /** Deletes a customer for good; the name of the tenant must be repeated, so it cannot happen by accident. */
    @PostMapping("/tenants/{id}/delete")
    public Map<String, Object> delete(@PathVariable UUID id, @RequestParam String confirmName) {
        UUID ownTenant = RequestContext.tenantId().orElse(null);
        return asSystem(() -> {
            String name = jdbc.queryForObject("select name from tenants where id = ?", String.class, id);
            if (!confirmName.equals(name)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Name stimmt nicht überein");
            }
            if (id.equals(ownTenant)) {
                throw ApiException.conflict("Der eigene Arbeitsbereich kann hier nicht gelöscht werden");
            }
            TenantPurgeService.Result result = purge.purge(id);
            return Map.of("deleted", true, "files", result.files());
        });
    }

    // --------------------------------------------------------------------------------------------- plans

    @GetMapping("/plans")
    public List<Map<String, Object>> plans() {
        return asSystem(() -> jdbc.queryForList(
                "select code, name, account_type, price_cents, currency, is_active from plans order by sort_order"));
    }

    @PutMapping("/plans/{code}")
    public List<Map<String, Object>> editPlan(@PathVariable String code, @RequestBody PlanEdit body) {
        if (body.priceCents() != null && (body.priceCents() < 0 || body.priceCents() > 1_000_000)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Preis ist ungültig");
        }
        return asSystem(() -> {
            int changed = jdbc.update("""
                    update plans set name = coalesce(?, name), price_cents = coalesce(?, price_cents),
                        is_active = coalesce(?, is_active), updated_at = now() where code = ?
                    """, body.name(), body.priceCents(), body.active(), code);
            if (changed == 0) {
                throw ApiException.notFound("plan");
            }
            return plans();
        });
    }

    // ------------------------------------------------------------------------------------------ settings

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return asSystem(settings::all);
    }

    @PutMapping("/settings")
    public Map<String, Object> putSetting(@RequestBody SettingBody body) {
        UUID actor = CurrentUser.required();
        return asSystem(() -> {
            settings.put(body.key(), body.value(), actor);
            return settings.all();
        });
    }

    // ----------------------------------------------------------------------------------------- invoices

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(@RequestParam(required = false) String status) {
        return asSystem(() -> {
            String filter = status == null || status.isBlank() ? null : status.toUpperCase(java.util.Locale.ROOT);
            return jdbc.queryForList("""
                    select i.id, i.invoice_number, i.tenant_id, t.name as tenant_name, i.plan_code, i.period_months,
                           i.amount_cents, i.status, i.method, i.customer_name, i.customer_email, i.issued_at,
                           i.due_at, i.paid_at, i.crypto_asset, i.crypto_amount
                    from billing_invoices i left join tenants t on t.id = i.tenant_id
                    where (?::text is null or i.status = ?) order by i.issued_at desc limit 500
                    """, filter, filter);
        });
    }

    @PostMapping("/invoices/{id}/mark-paid")
    public Map<String, Object> markPaid(@PathVariable UUID id) {
        UUID actor = CurrentUser.required();
        asSystem(() -> {
            billing.markPaid(id, actor);
            return null;
        });
        return Map.of("paid", true);
    }

    @PostMapping("/invoices/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) {
        asSystem(() -> {
            billing.cancel(id);
            return null;
        });
        return Map.of("cancelled", true);
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        byte[] bytes = asSystem(() -> billing.pdfAsAdmin(id));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice.pdf\"")
                .contentType(MediaType.APPLICATION_PDF).body(bytes);
    }

    @PostMapping("/lifecycle/run")
    public Map<String, Object> runLifecycle() {
        lifecycle.runOnce();
        return Map.of("ran", true);
    }

    // ---------------------------------------------------------------------------------- global services

    /**
     * Status of the services that serve every customer. They are configured in the server's environment file, not
     * here, so this is a read-only view: what is on, and which setting changes it.
     */
    @GetMapping("/integrations")
    public List<Map<String, Object>> integrations() {
        List<Map<String, Object>> list = new ArrayList<>();
        BurokratieProperties.Ai ai = properties.getAi();
        String mailHost = System.getenv("SPRING_MAIL_HOST");
        list.add(service("ai", "KI-Assistent und Klassifizierung", ai.isEnabled() && !"none".equals(ai.getProvider())
                && !ai.getBaseUrl().isBlank() && !ai.getApiKey().isBlank(),
                "Anbieter: " + ai.getProvider() + (ai.getBaseUrl().isBlank() ? "" : ", Adresse gesetzt")
                        + ", Monatsbudget " + ai.getMonthlyBudgetEur() + " €",
                "BC_AI_ENABLED, BC_AI_PROVIDER, BC_AI_BASE_URL, BC_AI_API_KEY, BC_AI_MONTHLY_BUDGET_EUR"));
        list.add(service("mail", "E-Mail-Versand (SMTP)", mailHost != null && !mailHost.isBlank(),
                "Absender: " + properties.getMail().getFrom(), "SPRING_MAIL_HOST, SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD, BC_MAIL_FROM"));
        list.add(service("malware", "Virenprüfung der Uploads",
                !"none".equals(properties.getSecurity().getMalwareScanner().getType()),
                "Typ: " + properties.getSecurity().getMalwareScanner().getType(), "BC_MALWARE_SCANNER, BC_CLAMAV_PATH"));
        list.add(service("ocr", "Texterkennung (OCR)", properties.getOcr().isEnabled(),
                "Engine: " + properties.getOcr().getEngine() + ", Sprachen: " + properties.getOcr().getLanguages(),
                "BC_OCR_ENABLED, BC_OCR_LANGUAGES"));
        list.add(service("storage", "Dateispeicher", true, "Typ: " + properties.getStorage().getType(),
                "BC_STORAGE_TYPE"));
        list.add(service("registration", "Registrierung für neue Kunden", properties.getSecurity().isRegistrationOpen(),
                properties.getSecurity().isRegistrationOpen() ? "offen für alle" : "geschlossen", "BC_REGISTRATION_OPEN"));
        return list;
    }

    private static Map<String, Object> service(String id, String name, boolean on, String detail, String setting) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("enabled", on);
        map.put("detail", detail);
        map.put("setting", setting);
        return map;
    }
}
