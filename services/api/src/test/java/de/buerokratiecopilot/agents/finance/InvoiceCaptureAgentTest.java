package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.InvoiceService;
import de.buerokratiecopilot.invoices.InvoiceValidation;
import de.buerokratiecopilot.invoices.SupplierRepository;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.invoices.domain.Supplier;

class InvoiceCaptureAgentTest {

    private InvoiceService invoiceService;
    private InvoiceRepository invoices;
    private SupplierRepository suppliers;
    private InvoiceCaptureAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        invoiceService = mock(InvoiceService.class);
        invoices = mock(InvoiceRepository.class);
        suppliers = mock(SupplierRepository.class);
        agent = new InvoiceCaptureAgent(invoiceService, invoices, suppliers);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    @Test
    void extractsFieldsAndRecordsInvoice() {
        String text = """
                Stadtwerke Musterstadt GmbH
                Rechnungsnummer: RE-2026-0412
                Rechnungsdatum: 15.03.2026
                Leistungsdatum: 01.03.2026
                Fällig am 29.03.2026
                Netto 80,67 EUR
                Umsatzsteuer 15,33 EUR
                Bruttobetrag: 96,00 EUR
                IBAN DE02 1234 5678 9012 3456 78
                """;
        Invoice invoice = new Invoice(UUID.randomUUID(), tenantId, "RE-2026-0412");
        when(invoices.findByTenantIdAndSupplierIdAndInvoiceNumber(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(invoiceService.record(any(), any())).thenReturn(new InvoiceService.Recorded(invoice,
                List.of(), new InvoiceValidation.Result("OK", List.of(), false, Map.of())));

        AgentResult result = agent.run(ctx(Map.of("rawText", text)));

        assertTrue(result.ok());
        assertEquals("RE-2026-0412", result.meta().get("invoiceNumber"));
        assertEquals("80.67", result.meta().get("netAmount"));
        assertEquals("15.33", result.meta().get("taxAmount"));
        assertEquals("96.00", result.meta().get("grossAmount"));
        assertEquals("2026-03-15", result.meta().get("issueDate"));
        assertEquals("2026-03-01", result.meta().get("deliveryDate"));
        assertEquals("2026-03-29", result.meta().get("dueDate"));
        assertEquals(1, result.produced().size());
        assertEquals("invoice", result.produced().get(0).kind());

        ArgumentCaptor<InvoiceService.InvoiceRequest> captor = ArgumentCaptor
                .forClass(InvoiceService.InvoiceRequest.class);
        verify(invoiceService).record(captor.capture(), any());
        assertEquals("RE-2026-0412", captor.getValue().invoiceNumber());
        assertEquals(new BigDecimal("96.00"), captor.getValue().grossAmount());
        assertEquals(new BigDecimal("80.67"), captor.getValue().netAmount());
    }

    @Test
    void reportsDuplicateInsteadOfRecording() {
        String text = "Lieferant Stadtwerke Musterstadt GmbH\nRechnungsnummer RE-2026-0412\n"
                + "Brutto 96,00 EUR";
        Supplier supplier = new Supplier(UUID.randomUUID(), tenantId, "Stadtwerke Musterstadt GmbH");
        when(suppliers.findByTenantIdAndName(tenantId, "Stadtwerke Musterstadt GmbH"))
                .thenReturn(Optional.of(supplier));
        when(invoices.findByTenantIdAndSupplierIdAndInvoiceNumber(tenantId, supplier.getId(),
                "RE-2026-0412"))
                .thenReturn(Optional.of(new Invoice(UUID.randomUUID(), tenantId, "RE-2026-0412")));

        AgentResult result = agent.run(ctx(Map.of("rawText", text)));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("bereits erfasst"));
        verify(invoiceService, never()).record(any(), any());
    }

    @Test
    void skipsHonestlyWhenMandatoryFieldsMissing() {
        AgentResult result = agent.run(ctx(Map.of("rawText", "Irgendein Brief ohne Rechnungsnummer")));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Rechnungsnummer"));
        verify(invoiceService, never()).record(any(), any());
    }

    @Test
    void emptyTextIsSkipped() {
        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("kein Text"));
    }
}
