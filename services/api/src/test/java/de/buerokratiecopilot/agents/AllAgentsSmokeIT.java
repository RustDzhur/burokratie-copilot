package de.buerokratiecopilot.agents;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentManifest;
import de.buerokratiecopilot.agents.core.AgentRegistry;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * Runs <strong>every</strong> catalogue agent once in a real tenant scope and records what it did.
 *
 * <p>The other agent tests prove one behaviour of one agent with hand-made input. This harness
 * answers the question that none of them answers: does <em>each</em> of the 126 agents survive a
 * call with the same minimal context ({@code tenantId}, an actor, trigger {@code smoke}, no inputs)
 * on an otherwise empty tenant? The contract in {@code docs/agents/RUNTIME.md} says an agent either
 * does its work or refuses honestly with a readable summary — it never throws. This test is the
 * executable form of that contract.</p>
 *
 * <p>Status per agent:</p>
 * <ul>
 *   <li>{@code OK} — {@code ok=true}: the agent produced something or completed its work.</li>
 *   <li>{@code REFUSED} — {@code ok=false} without an exception: expected on empty data, not a
 *       bug. The reason is {@code meta.reason} when the agent emits one, otherwise only the
 *       German summary is available.</li>
 *   <li>{@code ERROR} — the agent threw (or returned {@code null}): a real defect. Any ERROR fails
 *       the test and names the agent.</li>
 * </ul>
 *
 * <p>Every run gets its own transaction, opened <em>after</em> the tenant context is set — the
 * data source binds {@code app.tenant_id} when the connection is checked out (see
 * {@code TenantAwareDataSource} and {@code AgentRunner}), so the order matters for row level
 * security. One failing agent rolls back its own transaction and the harness continues.</p>
 *
 * <p>Artifacts: {@code target/agent-smoke-report.json} (machine-readable) and
 * {@code docs/agents/AGENT_VERIFICATION.md} (the report for the catalogue).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AllAgentsSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(AllAgentsSmokeIT.class);

    private static final int CATALOGUE_SIZE = 126;

    /**
     * A fixed instant keeps the run reproducible: agents that reason about "today" get the same
     * day on every execution instead of flipping at midnight.
     */
    private static final Instant NOW = Instant.parse("2026-10-27T09:00:00Z");

    private static final String UNSPECIFIED_REASON = "UNSPECIFIED";

    @Autowired
    private AgentRegistry registry;

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("all 126 agents run in a tenant scope: each works or refuses honestly, none throws")
    void allAgentsRunOrRefuseHonestly() throws Exception {
        var tenant = factory.createTenantWithOwner(
                "agent-smoke-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de");
        var actor = factory.ownerOf(tenant);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        List<AgentOutcome> outcomes = new ArrayList<>();
        long started = System.nanoTime();
        for (AgentManifest manifest : registry.all()) {
            outcomes.add(runOne(manifest, tenant.getId(), actor.getId(), transactions));
        }
        long totalMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        outcomes.sort(Comparator.comparing(AgentOutcome::id));

        SmokeReport report = SmokeReport.of(Instant.now(), tenant.getId(), actor.getId(),
                CATALOGUE_SIZE, totalMs, outcomes);
        writeArtifacts(report);

        List<String> errors = outcomes.stream()
                .filter(outcome -> outcome.status() == Status.ERROR)
                .map(outcome -> outcome.id() + ": " + outcome.exception())
                .toList();

        assertThat(outcomes.size())
                .as("every catalogue manifest must be exercised; the registry loaded fewer than "
                        + "expected (check the agent manifest resources and the classpath scan)")
                .isEqualTo(CATALOGUE_SIZE);
        assertThat(outcomes.stream().filter(AgentOutcome::implemented).count())
                .as("every agent must have an implementation bean")
                .isEqualTo(CATALOGUE_SIZE);
        assertThat(errors)
                .as("agents threw instead of returning an AgentResult; see "
                        + "target/agent-smoke-report.json and docs/agents/AGENT_VERIFICATION.md")
                .isEmpty();

        log.info("agent smoke: {} OK, {} REFUSED ({} with reason code), {} ERROR in {} ms "
                        + "(tenant {})",
                report.ok(), report.refused(), report.refusedWithReasonCode(), report.error(),
                totalMs, tenant.getId());
    }

    private AgentOutcome runOne(AgentManifest manifest, UUID tenantId, UUID actorUserId,
                                TransactionTemplate transactions) {
        String id = manifest.id();
        Agent implementation = registry.implementation(id).orElse(null);
        if (implementation == null) {
            return AgentOutcome.error(manifest, false, 0,
                    "Kein Agent-Bean für das Manifest gefunden");
        }

        AgentContext context = new AgentContext(tenantId, actorUserId, "smoke", NOW, Map.of());
        long started = System.nanoTime();
        try {
            AgentResult result = RequestContext.runAs(tenantId, () -> {
                RequestContext.setUserId(actorUserId);
                RequestContext.setActorType(RequestContext.ActorType.SYSTEM);
                return transactions.execute(status -> implementation.run(context));
            });
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (result == null) {
                return AgentOutcome.error(manifest, true, durationMs,
                        "Agent gab null statt eines AgentResult zurück");
            }
            String reason = result.meta().get("reason");
            Status status = result.ok() ? Status.OK : Status.REFUSED;
            return new AgentOutcome(id, manifest.name(), manifest.category(), true, status,
                    result.ok(), result.summary(), blankToNull(reason), durationMs, null, null,
                    result.produced().size(), result.meta());
        }
        catch (Exception e) {
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            log.error("agent smoke: {} threw", id, e);
            return AgentOutcome.error(manifest, true, durationMs, e);
        }
        finally {
            RequestContext.clear();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void writeArtifacts(SmokeReport report) throws IOException {
        Path repoRoot = repoRoot();
        Path target = repoRoot.resolve("services/api/target");
        Files.createDirectories(target);
        Path json = target.resolve("agent-smoke-report.json");
        objectMapper.copy()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writerWithDefaultPrettyPrinter()
                .writeValue(json.toFile(), report.asJson());

        Path markdown = repoRoot.resolve("docs/agents/AGENT_VERIFICATION.md");
        Files.createDirectories(markdown.getParent());
        Files.writeString(markdown, report.asMarkdown(), UTF_8);
        log.info("agent smoke: wrote {} and {}", json, markdown);
    }

    /**
     * The repository root, resolved from the working directory. Failsafe runs with the module as
     * working directory ({@code services/api}), so the root is two levels up; an IDE may start in
     * the root. The {@code docs/agents} directory is the marker for the root.
     */
    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && current != null; i++) {
            if (Files.isDirectory(current.resolve("docs/agents"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Cannot find the repository root (docs/agents) from " + Path.of("").toAbsolutePath());
    }

    private enum Status {
        OK, REFUSED, ERROR
    }

    /** What one agent did during the smoke run. */
    private record AgentOutcome(String id, String name, String category, boolean implemented,
                                Status status, boolean ok, String summary, String reason,
                                long durationMs, String exception, String stackTrace, int produced,
                                Map<String, String> meta) {

        static AgentOutcome error(AgentManifest manifest, boolean implemented, long durationMs,
                                  String message) {
            return new AgentOutcome(manifest.id(), manifest.name(), manifest.category(), implemented,
                    Status.ERROR, false, null, null, durationMs, message, null, 0, Map.of());
        }

        static AgentOutcome error(AgentManifest manifest, boolean implemented, long durationMs,
                                  Exception exception) {
            return new AgentOutcome(manifest.id(), manifest.name(), manifest.category(), implemented,
                    Status.ERROR, false, null, null, durationMs,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    stackTrace(exception), 0, Map.of());
        }

        private static String stackTrace(Exception exception) {
            StringWriter writer = new StringWriter();
            exception.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        }

        Map<String, Object> asJson() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", name);
            row.put("category", category);
            row.put("implemented", implemented);
            row.put("status", status.name());
            row.put("ok", ok);
            row.put("summary", summary);
            row.put("reason", reason);
            row.put("reasonClass", reasonClass());
            row.put("durationMs", durationMs);
            row.put("exception", exception);
            row.put("stackTrace", stackTrace);
            row.put("produced", produced);
            row.put("meta", meta);
            return row;
        }

        /**
         * A short, comparable bucket for the refusal. The agent's own {@code meta.reason} wins;
         * when it does not emit one (most agents use {@code skipped}/{@code failed} with a German
         * summary), the bucket is derived from that summary. The report labels the second case as
         * derived so nobody mistakes it for a machine-readable contract.
         */
        String reasonClass() {
            if (status != Status.REFUSED) {
                return null;
            }
            if (reason != null) {
                return switch (reason.toUpperCase(Locale.ROOT)) {
                    case "MISSING_REQUIRED", "INVALID_INPUT" -> "NO_INPUT";
                    default -> reason.toUpperCase(Locale.ROOT);
                };
            }
            String text = summary == null ? "" : summary.toLowerCase(Locale.ROOT);
            if (text.contains("verbundenen systeme") || text.contains("verbundene systeme")
                    || text.contains("caldav-verbindung") || text.contains("anbindung")
                    || text.contains("quellsystem")) {
                return "NO_SOURCE";
            }
            if (text.contains("bereits")) {
                return "ALREADY_DONE";
            }
            if (text.contains("deaktiviert") || text.contains("nicht verfügbar")
                    || text.contains("konfiguriert")) {
                return "NOT_CONFIGURED";
            }
            if (text.contains("nicht gefunden") || text.contains("nicht vorhanden")
                    || text.contains("unbekannt")) {
                return "NOT_FOUND";
            }
            if (text.contains("keine berechtigung") || text.contains("nicht erlaubt")
                    || text.contains("verweigert") || text.contains("unzulässig")) {
                return "GUARD";
            }
            if (text.contains("fehlt") || text.contains("fehlen") || text.contains("angegeben")
                    || text.contains("erforderlich") || text.contains("benötigt")
                    || text.contains("keine eingabe")) {
                return "NO_INPUT";
            }
            if (text.contains("zu wenige") || text.contains("keine daten")
                    || text.contains("keine ") || text.contains("kein ")) {
                return "NO_DATA";
            }
            return UNSPECIFIED_REASON;
        }
    }

    /**
     * The aggregated run. Serialised as JSON and rendered as Markdown; both artifacts are written
     * even when the test fails, so a broken agent leaves a trace instead of only a stack trace.
     */
    private record SmokeReport(Instant generatedAt, UUID tenantId, UUID actorUserId,
                               int catalogueSize, int checked, int implemented, int ok, int refused,
                               int error, long durationMs, List<AgentOutcome> agents) {

        static SmokeReport of(Instant generatedAt, UUID tenantId, UUID actorUserId,
                              int catalogueSize, long durationMs, List<AgentOutcome> agents) {
            int implemented = (int) agents.stream().filter(AgentOutcome::implemented).count();
            int ok = (int) agents.stream().filter(a -> a.status() == Status.OK).count();
            int refused = (int) agents.stream().filter(a -> a.status() == Status.REFUSED).count();
            int error = (int) agents.stream().filter(a -> a.status() == Status.ERROR).count();
            return new SmokeReport(generatedAt, tenantId, actorUserId, catalogueSize,
                    agents.size(), implemented, ok, refused, error, durationMs, List.copyOf(agents));
        }

        int refusedWithReasonCode() {
            return (int) agents.stream()
                    .filter(agent -> agent.status() == Status.REFUSED && agent.reason() != null)
                    .count();
        }

        Map<String, Object> asJson() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("generatedAt", generatedAt.toString());
            root.put("tenantId", tenantId.toString());
            root.put("actorUserId", actorUserId.toString());
            root.put("catalogueSize", catalogueSize);
            root.put("checked", checked);
            root.put("implemented", implemented);
            root.put("ok", ok);
            root.put("refused", refused);
            root.put("refusedWithReasonCode", refusedWithReasonCode());
            root.put("error", error);
            root.put("durationMs", durationMs);
            root.put("reasonCounts", reasonCounts());
            root.put("reasonCodeCounts", reasonCodeCounts());
            root.put("agents", agents.stream().map(AgentOutcome::asJson).toList());
            return root;
        }

        /** Refusals grouped by the effective bucket (own reason code or derived from summary). */
        Map<String, Long> reasonCounts() {
            return agents.stream()
                    .filter(agent -> agent.status() == Status.REFUSED)
                    .collect(Collectors.groupingBy(AgentOutcome::reasonClass,
                            () -> new java.util.TreeMap<String, Long>(), Collectors.counting()));
        }

        /** Refusals grouped by the code the agent actually emitted, {@code UNSPECIFIED} if none. */
        Map<String, Long> reasonCodeCounts() {
            return agents.stream()
                    .filter(agent -> agent.status() == Status.REFUSED)
                    .collect(Collectors.groupingBy(agent -> agent.reason() == null
                                    ? UNSPECIFIED_REASON : agent.reason(),
                            () -> new java.util.TreeMap<String, Long>(), Collectors.counting()));
        }

        private String reasonCodeSummary() {
            Map<String, Long> codes = new java.util.TreeMap<>(reasonCodeCounts());
            Long withoutCode = codes.remove(UNSPECIFIED_REASON);
            String emitted = codes.isEmpty() ? "keine"
                    : codes.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(Collectors.joining(", "));
            return emitted + (withoutCode == null ? "" : "; ohne Code: " + withoutCode);
        }

        String asMarkdown() {
            StringBuilder out = new StringBuilder();
            out.append("# Agenten-Verifikation — Smoke-Lauf\n\n");
            out.append("Stand: ").append(generatedAt).append(" · Datenbank `burokratie_test_smoke`")
                    .append(" · Tenant `").append(tenantId).append("`\n\n");
            out.append("Dieser Report wird von `AllAgentsSmokeIT` erzeugt: alle ")
                    .append(catalogueSize)
                    .append(" Agenten werden mit demselben minimalen Kontext (`trigger=smoke`, ")
                    .append("keine Eingaben, leerer Tenant) aufgerufen. Ein Lauf ist\n")
                    .append("**OK**, wenn der Agent etwas getan hat; **REFUSED**, wenn er ohne ")
                    .append("Daten ehrlich ablehnt (kein Fehler); **ERROR**, wenn er eine ")
                    .append("Ausnahme geworfen hat.\n\n");
            out.append("## Ergebnis\n\n");
            out.append("| Katalog | Geprüft | Implementiert | OK | REFUSED | davon mit Reason-Code ")
                    .append("| ERROR | Dauer |\n");
            out.append("|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            out.append("| ").append(catalogueSize).append(" | ").append(checked).append(" | ")
                    .append(implemented).append(" | ").append(ok).append(" | ").append(refused)
                    .append(" | ").append(refusedWithReasonCode()).append(" | ").append(error)
                    .append(" | ").append(durationMs).append(" ms |\n\n");
            if (error == 0 && checked == catalogueSize && implemented == catalogueSize) {
                out.append("**0 ERROR** — kein Agent hat eine unerwartete Ausnahme geworfen; ")
                        .append("alle ").append(catalogueSize)
                        .append(" Agenten sind implementiert und wurden ausgeführt.\n\n");
            }
            else {
                out.append("**").append(error)
                        .append(" ERROR**, ").append(checked).append("/").append(catalogueSize)
                        .append(" geprüft, ").append(implemented).append("/").append(catalogueSize)
                        .append(" implementiert — siehe Abschnitt „Fehler“ bzw. die Assertions ")
                        .append("des Tests.\n\n");
            }

            out.append("## Agenten\n\n");
            out.append("„Grund“ nennt den Reason-Code des Agenten und seine Summary. Nur ")
                    .append(refusedWithReasonCode()).append(" von ").append(refused)
                    .append(" Refusals setzen `meta.reason`; bei den übrigen ist die deutsche ")
                    .append("Summary die Begründung. „Für echte Arbeit nötig“ ist aus beidem ")
                    .append("abgeleitet.\n\n");
            out.append("| Agent | Kategorie | Status | Grund | Für echte Arbeit nötig |\n");
            out.append("|---|---|---|---|---|\n");
            for (AgentOutcome agent : agents) {
                out.append("| `").append(agent.id()).append("` | ").append(agent.category())
                        .append(" | ").append(agent.status()).append(" | ")
                        .append(escapeCell(reasonForTable(agent)))
                        .append(" | ").append(escapeCell(neededFor(agent))).append(" |\n");
            }
            out.append('\n');

            Map<String, Long> reasons = reasonCounts();
            out.append("## Gründe für REFUSED\n\n");
            out.append("Die Klasse kommt aus `meta.reason`, wenn der Agent einen Code setzt; ")
                    .append("sonst ist sie aus der Summary abgeleitet. Maschinenlesbare Codes: ")
                    .append(reasonCodeSummary()).append(".\n\n");
            if (reasons.isEmpty()) {
                out.append("Keine Refusals.\n\n");
            }
            else {
                out.append("| Grundklasse | Anzahl | Bedeutung |\n|---|---:|---|\n");
                reasons.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .forEach(entry -> out.append("| ").append(escapeCell(entry.getKey()))
                                .append(" | ").append(entry.getValue()).append(" | ")
                                .append(escapeCell(meaningOf(entry.getKey()))).append(" |\n"));
                out.append('\n');
            }

            out.append("## Fehler\n\n");
            List<AgentOutcome> failures = agents.stream()
                    .filter(agent -> agent.status() == Status.ERROR).toList();
            if (failures.isEmpty()) {
                out.append("Keine. Alle Agenten haben mit einem `AgentResult` geantwortet.\n\n");
            }
            else {
                for (AgentOutcome failure : failures) {
                    out.append("### `").append(failure.id()).append("`\n\n");
                    out.append("```\n").append(failure.stackTrace() == null
                            ? failure.exception() : failure.stackTrace()).append("```\n\n");
                }
            }

            out.append("## Reproduktion\n\n");
            out.append("```bash\n");
            out.append("cd services/api\n");
            out.append("BC_TEST_DB_NAME=burokratie_test_smoke ./mvnw -q verify ")
                    .append("-Dit.test=AllAgentsSmokeIT\n");
            out.append("```\n");
            return out.toString();
        }

        /** Reason code and summary for the table, without repeating an absent code. */
        private static String reasonForTable(AgentOutcome agent) {
            if (agent.status() == Status.ERROR) {
                return "—";
            }
            String prefix = agent.reason() == null ? "" : agent.reason() + " — ";
            return prefix + shorten(agent.summary());
        }

        private static String shorten(String value) {
            if (value == null) {
                return "";
            }
            String oneLine = value.replace('\n', ' ').replace('\r', ' ').strip();
            return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "…";
        }

        private static String meaningOf(String reasonClass) {
            return switch (reasonClass) {
                case "NO_INPUT" -> "Pflicht-Eingaben fehlen (Objekt-ID, Zeitraum, Text …).";
                case "NO_DATA" -> "Der Tenant hat keine passenden Daten (Dokumente, Rechnungen, "
                        + "Aufgaben, Verträge …).";
                case "NO_SOURCE" -> "Quellsystem/Integration ist nicht verbunden oder konfiguriert.";
                case "NOT_FOUND" -> "Das referenzierte Objekt existiert nicht oder gehört einem "
                        + "anderen Tenant.";
                case "NOT_CONFIGURED" -> "Feature oder Einstellung ist nicht aktiviert/konfiguriert.";
                case "ALREADY_DONE" -> "Die Arbeit ist bereits erledigt; der Agent ist idempotent.";
                case "GUARD" -> "Eine Regel (Freigabe, Legal Hold, Berechtigung) hat den Lauf "
                        + "bewusst verweigert.";
                default -> "Kein Reason-Code und keine eindeutige Summary-Klasse; Summary lesen.";
            };
        }

        /**
         * What the agent needs before it can do real work. The bucket already combines
         * {@code meta.reason} with the summary; the text names the concrete next step.
         */
        private static String neededFor(AgentOutcome agent) {
            if (agent.status() == Status.OK) {
                return "Nichts — der Agent hat auf dem leeren Tenant bereits gearbeitet.";
            }
            if (agent.status() == Status.ERROR) {
                return "Bug: Ausnahme beheben; siehe Abschnitt „Fehler“.";
            }
            return switch (agent.reasonClass()) {
                case "NO_INPUT" -> "Eingaben aus dem Manifest mitgeben (z. B. Objekt-ID, Zeitraum, "
                        + "Text).";
                case "NO_DATA" -> "Daten im Tenant anlegen (Dokumente, Rechnungen, Aufgaben, "
                        + "Verträge …).";
                case "NO_SOURCE" -> "Quellsystem verbinden/konfigurieren (Postfach, Kalender, "
                        + "Integration).";
                case "NOT_FOUND" -> "Existierendes Objekt referenzieren; die ID gehört nicht zum "
                        + "Tenant oder existiert nicht.";
                case "NOT_CONFIGURED" -> "Feature/Konfiguration aktivieren (OCR, KI, Integration, "
                        + "Zugangsdaten).";
                case "ALREADY_DONE" -> "Nichts — der Agent meldet, dass die Arbeit bereits "
                        + "erledigt ist.";
                case "GUARD" -> "Voraussetzungen/Freigaben schaffen; der Agent hat eine Regel "
                        + "durchgesetzt.";
                default -> "Eingaben oder Daten gemäß Manifest bereitstellen; die Begründung steht "
                        + "in der Summary.";
            };
        }

        private static String escapeCell(String value) {
            return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
        }
    }
}
