package de.buerokratiecopilot.agents.healthcare;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code healthcare-billing} — Leistungsabrechnung.
 *
 * <p>Prepares a billing draft: patient, treatment date, fee schedule, the service codes and the
 * documents that evidence the treatment. A position is only accepted when a document of that patient
 * on that date mentions the code; everything else is <em>rejected</em>, not estimated. The platform
 * has no fee-schedule master data, so the agent states that amounts are not available instead of
 * inventing prices — it also makes no medical decision and adds no diagnosis.</p>
 */
@Component
public class HealthcareBillingAgent implements Agent {

    static final String ARTIFACT_KIND = "healthcare-billing";
    static final List<String> FEE_SCHEDULES = List.of("GOÄ", "EBM", "PRIVATE");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]{0,15}");
    private static final int MAX_CODES = 100;
    private static final int MAX_DOCUMENTS = 50;

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public HealthcareBillingAgent(JdbcTemplate jdbc, AgentTaskFactory tasks,
                                  AgentArtifactStore artifacts) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "healthcare-billing";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID patientId = AgentInputs.uuid(context.input("patientContactId"));
        if (patientId == null) {
            return AgentResult.refused("NO_INPUT",
                    "Für die Abrechnung wird eine gültige patientContactId benötigt.");
        }
        String dateRaw = context.input("treatmentDate");
        if (AgentInputs.text(dateRaw) == null) {
            return AgentResult.refused("NO_INPUT",
                    "Für die Abrechnung wird ein Behandlungstag benötigt.");
        }
        LocalDate treatmentDate = AgentInputs.date(dateRaw);
        if (treatmentDate == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "Der Behandlungstag ist ungültig: " + dateRaw);
        }
        List<String> codes = AgentInputs.distinct(AgentInputs.list(context.input("serviceCodes")),
                MAX_CODES);
        if (codes.isEmpty()) {
            return AgentResult.refused("NO_INPUT",
                    "Es wurden keine Leistungspositionen angegeben (serviceCodes).");
        }
        String feeSchedule = normaliseFeeSchedule(context.input("feeSchedule"));
        if (feeSchedule == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "Unbekannte Gebührenordnung: " + context.input("feeSchedule")
                            + " (erlaubt: " + String.join(", ", FEE_SCHEDULES) + ")");
        }
        String insurer = AgentInputs.text(context.input("insurer"));

        List<Map<String, Object>> patients = jdbc.queryForList("""
                select id, display_name, email::text as email
                from contacts
                where tenant_id = ? and id = ? and deleted_at is null
                """, context.tenantId(), patientId);
        if (patients.isEmpty()) {
            return AgentResult.refused("NOT_FOUND",
                    "Der Kontakt " + patientId + " wurde nicht gefunden (oder gehört zu einem "
                            + "anderen Konto).");
        }
        Map<String, Object> patient = patients.getFirst();
        String patientName = String.valueOf(patient.get("display_name"));

        List<Map<String, Object>> documents = jdbc.queryForList("""
                select d.id, d.original_filename, d.extracted_text
                from documents d
                where d.tenant_id = ? and d.deleted_at is null
                  and (d.sender_contact_id = ?
                       or exists (select 1 from document_extractions e
                                  where e.document_id = d.id and e.subject_type = 'CONTACT'
                                    and e.subject_id = ?))
                  and (d.document_date = ?
                       or (d.document_date is null
                           and (d.received_at at time zone 'Europe/Berlin')::date = ?))
                order by d.received_at desc
                limit ?
                """, context.tenantId(), patientId, patientId, java.sql.Date.valueOf(treatmentDate),
                java.sql.Date.valueOf(treatmentDate), MAX_DOCUMENTS);

        List<String> texts = documents.stream().map(document -> document.get("extracted_text") == null
                ? "" : String.valueOf(document.get("extracted_text"))).toList();
        List<Map<String, Object>> positions = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (String code : codes) {
            if (!CODE.matcher(code).matches()) {
                rejected.add(rejected(code, "Code ist nicht plausibel"));
                continue;
            }
            List<String> evidence = new ArrayList<>();
            for (int index = 0; index < documents.size(); index++) {
                if (documented(code, texts.get(index))) {
                    evidence.add(String.valueOf(documents.get(index).get("id")));
                }
            }
            if (evidence.isEmpty()) {
                rejected.add(rejected(code, documents.isEmpty()
                        ? "Keine Dokumentation zum Behandlungstag"
                        : "Code ist in keinem Dokument des Behandlungstags belegt"));
                continue;
            }
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("code", code);
            position.put("documented", true);
            position.put("evidenceDocumentIds", evidence);
            positions.add(position);
        }
        if (positions.isEmpty()) {
            return AgentResult.refused("NO_DOCUMENTATION",
                    "Keine der angegebenen Leistungen ist dokumentiert; nicht dokumentierte "
                            + "Leistungen werden nicht geschätzt. Abgewiesen: "
                            + String.join(", ", rejected.stream()
                                    .map(item -> String.valueOf(item.get("code"))).toList()) + ".",
                    Map.of("documents", String.valueOf(documents.size()),
                            "rejected", String.valueOf(rejected.size())));
        }

        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("patientContactId", patientId.toString());
        payload.put("patientName", patientName);
        payload.put("treatmentDate", treatmentDate.toString());
        payload.put("feeSchedule", feeSchedule);
        payload.put("insurer", insurer);
        payload.put("positions", positions);
        payload.put("rejected", rejected);
        payload.put("documentCount", documents.size());
        payload.put("amountsAvailable", false);
        payload.put("amountNote", "Für die Gebührenordnung " + feeSchedule + " sind keine "
                + "Preisstammdaten hinterlegt; Beträge werden nicht geschätzt.");
        payload.put("reviewRequired", true);
        payload.put("note", "Keine medizinische Bewertung und keine Diagnose; die Abrechnung wird "
                + "vor dem Versand fachlich geprüft.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, patientId.toString(),
                treatmentDate.toString());
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("healthcare-billing", artifact.id().toString(),
                "Abrechnungsentwurf " + patientName + " " + treatmentDate + ": "
                        + positions.size() + " belegte Position(en), " + rejected.size()
                        + " abgewiesen");

        String taskTitle = "Abrechnung prüfen: " + AgentInputs.truncate(patientName, 200) + " "
                + treatmentDate;
        String description = "Gebührenordnung " + feeSchedule + ". Belegte Positionen: "
                + String.join(", ", positions.stream()
                        .map(position -> String.valueOf(position.get("code"))).toList())
                + (rejected.isEmpty() ? "" : ". Abgewiesen: "
                        + String.join("; ", rejected.stream().map(item -> item.get("code") + " ("
                                + item.get("reason") + ")").toList()))
                + ". Beträge sind nicht hinterlegt und werden nicht geschätzt.";
        AgentTaskFactory.Request request = new AgentTaskFactory.Request(taskTitle, description,
                "Die Abrechnung ist fachlich geprüft, Beträge sind ergänzt und die Freigabe ist "
                        + "dokumentiert.",
                AgentInputs.dueAt(today, 7), Task.Priority.HIGH,
                List.of("abrechnung", "pruefung"), null, context.actorUserId());
        int created = 0;
        var optionalTask = tasks.createIfAbsent(context, request);
        if (optionalTask.isPresent()) {
            AgentTaskFactory.produce(context, optionalTask.get());
            created = 1;
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("patientContactId", patientId.toString());
        meta.put("treatmentDate", treatmentDate.toString());
        meta.put("feeSchedule", feeSchedule);
        meta.put("codes", String.valueOf(codes.size()));
        meta.put("positions", String.valueOf(positions.size()));
        meta.put("rejected", String.valueOf(rejected.size()));
        meta.put("documents", String.valueOf(documents.size()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("amountsAvailable", "false");
        meta.put("requiresReview", "true");
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Abrechnungsentwurf für " + patientName + " am " + treatmentDate
                + ": " + positions.size() + " von " + codes.size() + " Position(en) belegt, "
                + rejected.size() + " abgewiesen, " + created + " Prüfaufgabe(n). Beträge sind "
                + "nicht hinterlegt und werden nicht geschätzt.", context.produced(), meta);
    }

    /**
     * A code counts as documented when a document of the treatment day mentions it as a standalone
     * token. A single-digit code additionally needs a fee-schedule marker ("GOÄ 5", "Ziffer 5"),
     * because "5" appears in almost any text. Visible for tests.
     */
    public static boolean documented(String code, String text) {
        if (code == null || text == null || text.isBlank()) {
            return false;
        }
        String quoted = Pattern.quote(code.strip());
        Pattern pattern = code.strip().length() == 1
                ? Pattern.compile("(?iu)(?:go[äa]|ebm|ziffer|nr\\.?)\\s*0*" + quoted + "\\b")
                : Pattern.compile("(?iu)\\b0*" + quoted + "\\b");
        Matcher matcher = pattern.matcher(text);
        return matcher.find();
    }

    /** Accepts the German GOÄ spelling as well as ASCII GOA/GOE. Visible for tests. */
    public static String normaliseFeeSchedule(String raw) {
        String value = AgentInputs.text(raw);
        if (value == null) {
            return "GOÄ";
        }
        String upper = value.toUpperCase(Locale.ROOT).replace("GOA", "GOÄ")
                .replace("GOE", "GOÄ");
        return FEE_SCHEDULES.contains(upper) ? upper : null;
    }

    private static Map<String, Object> rejected(String code, String reason) {
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("code", code);
        rejected.put("reason", reason);
        return rejected;
    }
}
