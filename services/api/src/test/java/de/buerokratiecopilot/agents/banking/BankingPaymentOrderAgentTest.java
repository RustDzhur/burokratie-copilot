package de.buerokratiecopilot.agents.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.approvals.PaymentApprovalService;
import de.buerokratiecopilot.approvals.domain.Payment;
import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.SupplierRepository;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.invoices.domain.Supplier;

class BankingPaymentOrderAgentTest {

    private InvoiceRepository invoices;
    private SupplierRepository suppliers;
    private PaymentApprovalService payments;
    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private BankingPaymentOrderAgent agent;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID invoiceId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        invoices = mock(InvoiceRepository.class);
        suppliers = mock(SupplierRepository.class);
        payments = mock(PaymentApprovalService.class);
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new BankingPaymentOrderAgent(invoices, suppliers, payments, jdbc, tasks);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(tenantId, actorId, "manual",
                Instant.parse("2026-10-27T09:00:00Z"), inputs);
    }

    @Test
    void preparesAnApprovedInvoiceAndLeavesItAwaitingApproval() {
        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(invoiceId);
        when(invoice.getInvoiceNumber()).thenReturn("RE-2026-1");
        when(invoice.getStatus()).thenReturn(Invoice.Status.APPROVED);
        when(invoice.getApprovedAt()).thenReturn(Instant.parse("2026-10-20T09:00:00Z"));
        when(invoice.getDirection()).thenReturn(Invoice.Direction.INCOMING);
        when(invoice.getGrossAmount()).thenReturn(new BigDecimal("119.00"));
        when(invoice.getPaidAmount()).thenReturn(BigDecimal.ZERO);
        when(invoice.getCurrency()).thenReturn("EUR");
        when(invoice.getSupplierId()).thenReturn(supplierId);
        when(invoice.getIban()).thenReturn("DE89 3704 0044 0532 0130 00");
        when(invoice.isBankDetailsChanged()).thenReturn(false);
        Supplier supplier = mock(Supplier.class);
        when(supplier.getName()).thenReturn("Beispiel Handels GmbH");
        when(supplier.knownIban()).thenReturn("DE89370400440532013000");
        when(invoices.findByIdAndTenantIdAndDeletedAtIsNull(invoiceId, tenantId))
                .thenReturn(Optional.of(invoice));
        when(suppliers.findByIdAndTenantId(supplierId, tenantId)).thenReturn(Optional.of(supplier));
        when(jdbc.queryForObject(contains("from financial_accounts"), eq(Integer.class),
                any(Object.class), any(Object.class))).thenReturn(1);
        PaymentApprovalService.Prepared prepared = mock(PaymentApprovalService.Prepared.class);
        Payment payment = mock(Payment.class);
        when(prepared.payment()).thenReturn(payment);
        when(payment.getId()).thenReturn(paymentId);
        when(payments.prepare(any(), eq(actorId))).thenReturn(prepared);

        AgentResult result = agent.run(context(Map.of(
                "invoiceIds", invoiceId.toString(),
                "executionDate", "2026-11-01",
                "bankAccountId", accountId.toString(),
                "batchName", "Novemberlauf")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("prepared", "1").containsEntry("blocked", "0");
        assertThat(result.produced()).extracting(AgentResult.Produced::ref)
                .contains(paymentId.toString());
        assertThat(result.summary()).contains("Nichts an eine Bank übermittelt");
        verifyNoInteractions(tasks);
    }

    @Test
    void withoutInvoiceIdsNothingIsPrepared() {
        AgentResult result = agent.run(context(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine invoiceIds");
        verifyNoInteractions(payments, invoices, suppliers);
    }

    @Test
    void aChangedIbanBlocksThePaymentAndCreatesOneReviewTask() {
        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(invoiceId);
        when(invoice.getInvoiceNumber()).thenReturn("RE-2026-2");
        when(invoice.getStatus()).thenReturn(Invoice.Status.APPROVED);
        when(invoice.getApprovedAt()).thenReturn(Instant.parse("2026-10-20T09:00:00Z"));
        when(invoice.getDirection()).thenReturn(Invoice.Direction.INCOMING);
        when(invoice.getGrossAmount()).thenReturn(new BigDecimal("119.00"));
        when(invoice.getPaidAmount()).thenReturn(BigDecimal.ZERO);
        when(invoice.getSupplierId()).thenReturn(supplierId);
        when(invoice.getIban()).thenReturn("DE615001051700440532013000");
        when(invoice.isBankDetailsChanged()).thenReturn(false);
        Supplier supplier = mock(Supplier.class);
        when(supplier.getName()).thenReturn("Beispiel Handels GmbH");
        when(supplier.knownIban()).thenReturn("DE89370400440532013000");
        when(invoices.findByIdAndTenantIdAndDeletedAtIsNull(invoiceId, tenantId))
                .thenReturn(Optional.of(invoice));
        when(suppliers.findByIdAndTenantId(supplierId, tenantId)).thenReturn(Optional.of(supplier));
        when(jdbc.queryForObject(contains("from financial_accounts"), eq(Integer.class),
                any(Object.class), any(Object.class))).thenReturn(1);
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(context(Map.of(
                "invoiceIds", invoiceId.toString(),
                "executionDate", "2026-11-01",
                "bankAccountId", accountId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("prepared", "0").containsEntry("blocked", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::label)
                .anyMatch(label -> label.contains("Bankverbindung"));
        verifyNoInteractions(payments);
    }
}
