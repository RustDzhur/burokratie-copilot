package de.buerokratiecopilot.agents.legal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.contracts.ContractObligationRepository;
import de.buerokratiecopilot.contracts.ContractRepository;
import de.buerokratiecopilot.contracts.domain.Contract;
import de.buerokratiecopilot.contracts.domain.ContractObligation;

/**
 * {@code legal-contract-control} — Vertragskontrolle.
 *
 * <p>Reads the contract register for two windows: terms ending within the horizon and open
 * obligations that are due (or already missed). It reports; it does not terminate, renew or change
 * anything. The assessment is a pure function over rows so the arithmetic can be reviewed and
 * tested without a database.</p>
 */
@Component
public class ContractControlAgent implements Agent {

    static final int DEFAULT_HORIZON_DAYS = 90;
    private static final int MAX_LISTED = 50;
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ContractRepository contracts;
    private final ContractObligationRepository obligations;

    public ContractControlAgent(ContractRepository contracts, ContractObligationRepository obligations) {
        this.contracts = contracts;
        this.obligations = obligations;
    }

    @Override
    public String id() {
        return "legal-contract-control";
    }

    /** The two windows the agent reports on. */
    public record Assessment(List<ContractDeadline> contracts, List<ObligationDue> obligations) {

        public long overdueObligations() {
            return obligations.stream().filter(obligation -> obligation.daysLeft() < 0).count();
        }
    }

    /** A contract whose term ends inside the horizon. */
    public record ContractRow(UUID id, String number, String title, LocalDate endDate, String status,
                              BigDecimal costAmount, String currency) {
    }

    public record ContractDeadline(UUID id, String number, String title, LocalDate endDate,
                                   long daysLeft, String status, BigDecimal costAmount,
                                   String currency) {
    }

    /** An open obligation, independent of the contract table. */
    public record ObligationRow(UUID id, UUID contractId, String title, LocalDate dueDate,
                                String status) {
    }

    public record ObligationDue(UUID id, UUID contractId, String title, LocalDate dueDate,
                                long daysLeft, String status) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate today = LocalDate.ofInstant(context.now(), BERLIN);
        int horizonDays = parseHorizon(context.input("horizonDays"));
        boolean includeObligations = !"false".equalsIgnoreCase(context.input("includeObligations"));
        LocalDate horizonEnd = today.plusDays(horizonDays);

        List<Contract> found = contracts.findByTenantIdAndEndDateBetweenAndDeletedAtIsNull(
                context.tenantId(), today, horizonEnd);
        List<ContractRow> contractRows = found.stream()
                .map(contract -> new ContractRow(contract.getId(), contract.getContractNumber(),
                        contract.getTitle(), contract.getEndDate(),
                        contract.getStatus() == null ? "" : contract.getStatus().name(),
                        contract.getCostAmount(), contract.getCostCurrency()))
                .toList();

        List<ObligationRow> obligationRows = new ArrayList<>();
        if (includeObligations) {
            for (ContractObligation obligation : obligations.findRemindable(context.tenantId())) {
                if (obligation.getDueDate() != null
                        && !obligation.getDueDate().isAfter(horizonEnd)) {
                    obligationRows.add(new ObligationRow(obligation.getId(),
                            obligation.getContractId(), obligation.getTitle(),
                            obligation.getDueDate(),
                            obligation.getStatus() == null ? "" : obligation.getStatus().name()));
                }
            }
        }

        Assessment assessment = assess(contractRows, obligationRows, today);
        if (assessment.contracts().isEmpty() && assessment.obligations().isEmpty()) {
            return AgentResult.skipped("Im Zeitfenster von " + horizonDays + " Tagen enden keine "
                    + "Verträge und es sind keine offenen Vertragspflichten fällig.");
        }

        for (ContractDeadline deadline : assessment.contracts().stream().limit(MAX_LISTED).toList()) {
            context.addProduced("contract-deadline", deadline.id().toString(),
                    label(deadline) + " — endet am " + deadline.endDate()
                            + " (in " + deadline.daysLeft() + " Tagen)");
        }
        for (ObligationDue due : assessment.obligations().stream().limit(MAX_LISTED).toList()) {
            context.addProduced("contract-obligation", due.id().toString(),
                    due.title() + " — " + (due.daysLeft() < 0
                            ? "überfällig seit " + Math.abs(due.daysLeft()) + " Tagen"
                            : "fällig in " + due.daysLeft() + " Tagen"));
        }

        String summary = assessment.contracts().size() + " Vertrag/Verträge enden in den nächsten "
                + horizonDays + " Tagen; " + assessment.obligations().size()
                + " offene Vertragspflicht(en), davon " + assessment.overdueObligations()
                + " überfällig.";
        return AgentResult.done(summary, context.produced(), java.util.Map.of(
                "horizonDays", String.valueOf(horizonDays),
                "expiringContracts", String.valueOf(assessment.contracts().size()),
                "dueObligations", String.valueOf(assessment.obligations().size()),
                "overdueObligations", String.valueOf(assessment.overdueObligations()),
                "periodEnd", horizonEnd.toString()));
    }

    /** Pure assessment of both lists against one day. Visible for tests. */
    public static Assessment assess(List<ContractRow> contractRows,
                                    List<ObligationRow> obligationRows, LocalDate today) {
        List<ContractDeadline> deadlines = new ArrayList<>();
        for (ContractRow row : contractRows) {
            if (row.endDate() == null || row.endDate().isBefore(today)) {
                continue;
            }
            deadlines.add(new ContractDeadline(row.id(), row.number(), row.title(), row.endDate(),
                    ChronoUnit.DAYS.between(today, row.endDate()), row.status(), row.costAmount(),
                    row.currency()));
        }
        deadlines.sort(Comparator.comparing(ContractDeadline::endDate));

        List<ObligationDue> dues = new ArrayList<>();
        for (ObligationRow row : obligationRows) {
            if (row.dueDate() == null) {
                continue;
            }
            dues.add(new ObligationDue(row.id(), row.contractId(), row.title(), row.dueDate(),
                    ChronoUnit.DAYS.between(today, row.dueDate()), row.status()));
        }
        dues.sort(Comparator.comparing(ObligationDue::dueDate));
        return new Assessment(List.copyOf(deadlines), List.copyOf(dues));
    }

    private static String label(ContractDeadline deadline) {
        String number = deadline.number() == null || deadline.number().isBlank()
                ? "" : deadline.number() + ": ";
        return number + deadline.title();
    }

    static int parseHorizon(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_HORIZON_DAYS;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value < 1 || value > 3650 ? DEFAULT_HORIZON_DAYS : value;
        } catch (NumberFormatException e) {
            return DEFAULT_HORIZON_DAYS;
        }
    }
}
