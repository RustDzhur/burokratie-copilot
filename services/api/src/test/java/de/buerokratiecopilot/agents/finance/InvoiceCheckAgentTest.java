package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.InvoiceService;
import de.buerokratiecopilot.invoices.InvoiceValidation;
import de.buerokratiecopilot.invoices.SupplierRepository;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.shared.settings.SystemSettingRepository;

class InvoiceCheckAgentTest {

    private InvoiceService invoiceService;
    private InvoiceRepository invoiceRepository;
    private SupplierRepository supplierRepository;
    private JdbcTemplate jdbc;
    private SystemSettingRepository settings;
    private InvoiceCheckAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        invoiceService = mock(InvoiceService.class);
        invoiceRepository = mock(InvoiceRepository.class);
        supplierRepository = mock(SupplierRepository.class);
        jdbc = mock(JdbcTemplate.class);
        settings = mock(SystemSettingRepository.class);
        agent = new InvoiceCheckAgent(invoiceService, invoiceRepository, supplierRepository, jdbc,
                settings);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    @Test
    void checksInvoiceAndReportsFindings() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, tenantId, "RE-2026-0412");
        invoice.setDirection(Invoice.Direction.INCOMING);
        invoice.setStatus(Invoice.Status.RECEIVED);
        invoice.setGrossAmount(new BigDecimal("6000.00"));

        when(invoiceRepository.findByIdAndTenantIdAndDeletedAtIsNull(invoiceId, tenantId))
                .thenReturn(Optional.of(invoice));
        when(invoiceService.revalidate(invoiceId, tenantId)).thenReturn(new InvoiceValidation.Result(
                "NEEDS_REVIEW",
                List.of(new InvoiceValidation.Flag("MISSING_TAX", InvoiceValidation.Severity.WARNING,
                        "Umsatzsteuer fehlt", Map.of())),
                false, Map.of()));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(1);
        when(settings.findByTenantIdAndSettingKey(tenantId, "finance.invoice_check.max_gross_eur"))
                .thenReturn(Optional.empty());
        when(settings.findByTenantIdIsNullAndSettingKey("finance.invoice_check.max_gross_eur"))
                .thenReturn(Optional.empty());

        AgentResult result = agent.run(ctx(Map.of("invoiceId", invoiceId.toString())));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("checked"));
        assertEquals("1", result.meta().get("duplicates"));
        assertEquals("1", result.meta().get("overLimit"));
        assertEquals("1", result.meta().get("missingSupplier"));
        assertEquals(1, result.produced().size());
        assertEquals("invoice-check", result.produced().get(0).kind());
    }

    @Test
    void unknownInvoiceIsSkipped() {
        when(invoiceRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), eq(tenantId)))
                .thenReturn(Optional.empty());

        AgentResult result = agent.run(ctx(Map.of("invoiceId", UUID.randomUUID().toString())));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine Rechnung"));
    }
}
