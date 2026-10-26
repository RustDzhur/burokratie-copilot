package de.buerokratiecopilot.agents.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.InvoiceService;
import de.buerokratiecopilot.invoices.InvoiceValidation;
import de.buerokratiecopilot.invoices.SupplierRepository;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.shared.settings.SystemSetting;
import de.buerokratiecopilot.shared.settings.SystemSettingRepository;

/**
 * {@code invoice-check} — Rechnungsprüfung.
 *
 * <p>Checks incoming invoices against the module's rules and reports every discrepancy: a duplicate
 * (same supplier and number), a gross amount above the configured limit, a missing or vanished
 * counterparty, and the fresh validation state of the invoice module. Nothing is corrected — the
 * paper is what gets paid, so the agent reports and a person decides.</p>
 *
 * <p>Idempotent by nature: checking and revalidation overwrite the validation state with the same
 * outcome, and duplicates are counted, not created.</p>
 */
@Component
public class InvoiceCheckAgent implements Agent {

    private static final String LIMIT_SETTING = "finance.invoice_check.max_gross_eur";
    private static final BigDecimal DEFAULT_MAX_GROSS = new BigDecimal("5000");
    private static final int MAX_INVOICES = 100;

    /** One finding, comparable to the module's validation flags. */
    private record Finding(String code, String severity, String message) {
    }

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;
    private final SupplierRepository supplierRepository;
    private final JdbcTemplate jdbc;
    private final SystemSettingRepository settings;

    public InvoiceCheckAgent(InvoiceService invoiceService, InvoiceRepository invoiceRepository,
                             SupplierRepository supplierRepository, JdbcTemplate jdbc,
                             SystemSettingRepository settings) {
        this.invoiceService = invoiceService;
        this.invoiceRepository = invoiceRepository;
        this.supplierRepository = supplierRepository;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public String id() {
        return "invoice-check";
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<Invoice> targets = targets(context);
        if (targets.isEmpty()) {
            return AgentResult.skipped("Rechnungsprüfung: keine Rechnung gefunden (keine offenen "
                    + "eingehenden Rechnungen oder unbekannte invoiceId).");
        }
        BigDecimal maxGross = readLimit(context.tenantId());
        List<AgentResult.Produced> produced = new ArrayList<>();
        int findingsTotal = 0;
        int blocked = 0;
        int duplicates = 0;
        int overLimit = 0;
        int missingSupplier = 0;

        for (Invoice invoice : targets) {
            List<Finding> findings = check(context.tenantId(), invoice, maxGross);
            findingsTotal += findings.size();
            for (Finding finding : findings) {
                if ("BLOCKING".equals(finding.severity())) {
                    blocked++;
                }
                switch (finding.code()) {
                    case "DUPLICATE_NUMBER" -> duplicates++;
                    case "OVER_LIMIT" -> overLimit++;
                    case "NO_SUPPLIER", "SUPPLIER_MISSING" -> missingSupplier++;
                    default -> {
                    }
                }
            }
            String label = findings.isEmpty() ? "OK"
                    : findings.size() + " Befund(e): "
                            + findings.stream().map(Finding::code).limit(5).toList();
            produced.add(new AgentResult.Produced("invoice-check", invoice.getId().toString(),
                    "Rechnung " + invoice.getInvoiceNumber() + ": " + label));
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("checked", String.valueOf(targets.size()));
        meta.put("findings", String.valueOf(findingsTotal));
        meta.put("blocked", String.valueOf(blocked));
        meta.put("duplicates", String.valueOf(duplicates));
        meta.put("overLimit", String.valueOf(overLimit));
        meta.put("missingSupplier", String.valueOf(missingSupplier));
        meta.put("maxGrossEur", maxGross.toPlainString());
        return AgentResult.done(
                "Rechnungsprüfung: " + targets.size() + " Rechnung(en) geprüft, " + findingsTotal
                        + " Befund(e) (" + blocked + " blockierend, " + duplicates + " Dublette(n), "
                        + overLimit + " über Limit, " + missingSupplier + " ohne/ohne gültigen Lieferanten).",
                produced, meta);
    }

    private List<Invoice> targets(AgentContext context) {
        String input = context.input("invoiceId");
        if (input != null && !input.isBlank() && !"all".equalsIgnoreCase(input.trim())) {
            UUID id = FinanceSupport.parseUuid(input);
            if (id == null) {
                return List.of();
            }
            return invoiceRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, context.tenantId())
                    .map(List::of).orElseGet(List::of);
        }
        return invoiceRepository.search(context.tenantId(),
                List.of(Invoice.Status.RECEIVED, Invoice.Status.IN_REVIEW, Invoice.Status.APPROVED),
                List.of(Invoice.Direction.INCOMING), PageRequest.of(0, MAX_INVOICES)).getContent();
    }

    private List<Finding> check(UUID tenantId, Invoice invoice, BigDecimal maxGross) {
        List<Finding> findings = new ArrayList<>();

        InvoiceValidation.Result validation = invoiceService.revalidate(invoice.getId(), tenantId);
        for (InvoiceValidation.Flag flag : validation.flags()) {
            if (flag.severity() != InvoiceValidation.Severity.INFO) {
                findings.add(new Finding(flag.code(), flag.severity().name(), flag.message()));
            }
        }
        if (validation.blocksAutomaticPosting()) {
            findings.add(new Finding("BLOCKED", "BLOCKING",
                    "Die Rechnung blockiert die automatische Verbuchung"));
        }

        Integer duplicatesCount = jdbc.queryForObject("""
                select count(*) from invoices
                where tenant_id = ? and deleted_at is null and invoice_number = ?
                  and supplier_id is not distinct from ? and id <> ?
                """, Integer.class, tenantId, invoice.getInvoiceNumber(), invoice.getSupplierId(),
                invoice.getId());
        if (duplicatesCount != null && duplicatesCount > 0) {
            findings.add(new Finding("DUPLICATE_NUMBER", "BLOCKING",
                    duplicatesCount + " weitere Rechnung(en) mit derselben Nummer und demselben Lieferanten"));
        }

        if (invoice.getSupplierId() == null) {
            findings.add(new Finding("NO_SUPPLIER", "WARNING",
                    "Der Rechnung ist kein Lieferant zugeordnet"));
        }
        else if (supplierRepository.findById(invoice.getSupplierId()).isEmpty()) {
            findings.add(new Finding("SUPPLIER_MISSING", "WARNING",
                    "Der zugeordnete Lieferant existiert nicht (mehr)"));
        }

        if (invoice.getGrossAmount() != null && invoice.getGrossAmount().compareTo(maxGross) > 0) {
            findings.add(new Finding("OVER_LIMIT", "WARNING",
                    "Bruttobetrag " + FinanceSupport.money(invoice.getGrossAmount())
                            + " liegt über dem Limit von " + FinanceSupport.money(maxGross)));
        }

        if (invoice.getDueDate() == null) {
            findings.add(new Finding("NO_DUE_DATE", "INFO", "Kein Fälligkeitsdatum hinterlegt"));
        }
        return findings;
    }

    private BigDecimal readLimit(UUID tenantId) {
        String raw = settings.findByTenantIdAndSettingKey(tenantId, LIMIT_SETTING)
                .map(SystemSetting::valueAsString)
                .orElseGet(() -> settings.findByTenantIdIsNullAndSettingKey(LIMIT_SETTING)
                        .map(SystemSetting::valueAsString).orElse(null));
        BigDecimal parsed = FinanceSupport.parseAmount(raw);
        return parsed == null ? DEFAULT_MAX_GROSS : parsed;
    }
}
