package de.buerokratiecopilot.agents.hospitality;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code hospitality-no-show} — No-Show-Nachfassung.
 *
 * <p>Reads the reservation task, checks that the grace period has passed and prepares a factual
 * follow-up: a task and — when a guest address is known — a draft email with a new appointment
 * proposal. Repeated no-shows are marked internally; nobody is publicly rated. The draft is never
 * sent by the agent.</p>
 */
@Component
public class NoShowAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;
    private final OutboundMessageService outbound;

    public NoShowAgent(JdbcTemplate jdbc, AgentWorkspace workspace, OutboundMessageService outbound) {
        this.jdbc = jdbc;
        this.workspace = workspace;
        this.outbound = outbound;
    }

    @Override
    public String id() {
        return "hospitality-no-show";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID reservationId = AgentInputs.uuid(context.input("reservationId"));
        if (reservationId == null) {
            return AgentResult.skipped("No-Show: 'reservationId' fehlt oder ist keine gültige UUID.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, title, metadata::text as metadata
                from tasks
                where tenant_id = ? and id = ? and deleted_at is null
                """, context.tenantId(), reservationId);
        if (rows.isEmpty()) {
            return AgentResult.skipped("No-Show: Reservierung/Aufgabe " + reservationId
                    + " ist im Mandanten nicht vorhanden.");
        }
        Map<String, Object> row = rows.getFirst();
        String metadataRaw = String.valueOf(row.get("metadata"));
        Reservation reservation = parse(metadataRaw);
        if (reservation == null || reservation.at() == null) {
            return AgentResult.skipped("No-Show: Aufgabe " + reservationId
                    + " trägt keine Reservierungsdaten (reservationAt/guest).");
        }
        int graceMinutes = AgentInputs.integer(context.input("graceMinutes"), 20, 0, 240);
        Instant due = reservation.at().plusMinutes(graceMinutes)
                .atZone(AgentInputs.BERLIN).toInstant();
        if (context.now().isBefore(due)) {
            return AgentResult.skipped("No-Show: die Nachfrist von " + graceMinutes
                    + " Minuten ist noch nicht abgelaufen (Reservierung " + reservation.at() + ").");
        }
        int previous = AgentInputs.integer(context.input("repeatCount"), 0, 0, 100);
        int repeat = Math.max(previous, reservation.noShowCount()) + 1;
        String channel = AgentInputs.blankTo(context.input("channel"), "EMAIL").toUpperCase(Locale.ROOT);
        if (!List.of("EMAIL", "PHONE").contains(channel)) {
            return AgentResult.failed("No-Show: unbekannter Kanal '" + channel + "'.");
        }
        String sourceKey = "hospitality-no-show:" + reservationId;
        String title = "No-Show nachfassen: " + reservation.guest();
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey, title,
                "Reservierung: " + reservation.at() + " (" + reservation.partySize() + " Personen)"
                        + "\nGast: " + reservation.guest()
                        + (reservation.email() == null ? "" : " <" + reservation.email() + ">")
                        + "\nKanal: " + channel
                        + "\nNo-Show-Nummer: " + repeat
                        + "\n\nSachliche Nachfrage mit Terminvorschlag; keine öffentliche Bewertung, "
                        + "keine Schuldzuweisung. Der Agent versendet nichts.",
                repeat >= 2 ? Task.Priority.HIGH : Task.Priority.NORMAL,
                context.now().plus(Duration.ofDays(1)),
                List.of("hospitality", "no-show", channel.toLowerCase(Locale.ROOT)),
                Map.of("reservationId", reservationId.toString(), "repeatCount", repeat,
                        "channel", channel));
        if (!task.created()) {
            context.addProduced("task", task.id().toString(), "Bestehende No-Show-Nachfassung");
            return AgentResult.done("No-Show für " + reservation.guest()
                    + " ist bereits nachgefasst (" + task.id() + ").", context.produced(),
                    Map.of("existing", "true", "taskId", task.id().toString()));
        }
        context.addProduced("task", task.id().toString(), title);
        boolean draft = false;
        if ("EMAIL".equals(channel) && reservation.email() != null) {
            try {
                String body = "Guten Tag " + reservation.guest() + ",\n\n"
                        + "wir haben Ihre Reservierung für " + reservation.at()
                        + " vorgemerkt, Sie waren leider nicht bei uns. "
                        + "Wenn Sie möchten, schlagen wir Ihnen einen neuen Termin vor. "
                        + "Bitte antworten Sie kurz, ob es Ihnen passt.\n\n"
                        + "Freundliche Grüße\nIhr Team";
                UUID messageId = outbound.draft(new OutboundMessageService.DraftRequest(
                        context.tenantId(), null, "EMAIL",
                        "Ihre Reservierung bei uns — Terminvorschlag",
                        body, null, null, List.of(reservation.email()), List.of(), null,
                        sourceKey + ":draft", context.actorUserId()));
                context.addProduced("email_draft", messageId.toString(),
                        "No-Show-Nachfrage an " + reservation.email());
                draft = true;
            }
            catch (RuntimeException ex) {
                // The task exists; a missing draft is reported in the summary, not hidden.
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("reservationId", reservationId.toString());
        meta.put("repeatCount", String.valueOf(repeat));
        meta.put("channel", channel);
        meta.put("draftCreated", String.valueOf(draft));
        meta.put("sent", "false");
        return AgentResult.done("No-Show für " + reservation.guest() + " vom " + reservation.at()
                + " erfasst (Nr. " + repeat + "); Nachfassaufgabe angelegt"
                + (draft ? " und E-Mail-Entwurf vorbereitet" : "")
                + ", nichts versendet.", context.produced(), meta);
    }

    private Reservation parse(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            Map<String, Object> metadata = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            LocalDateTime at = AgentInputs.localDateTime(text(metadata.get("reservationAt")));
            if (at == null) {
                return null;
            }
            Integer party = metadata.get("reservationPartySize") instanceof Number number
                    ? number.intValue() : AgentInputs.integer(text(metadata.get("partySize")));
            Integer noShow = metadata.get("noShowCount") instanceof Number number
                    ? number.intValue() : AgentInputs.integer(text(metadata.get("noShowCount")));
            return new Reservation(at, text(metadata.get("guestName")),
                    text(metadata.get("guestEmail")), party == null ? 0 : party,
                    noShow == null ? 0 : noShow);
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    /** The reservation data carried on the task. */
    public record Reservation(LocalDateTime at, String guest, String email, int partySize,
                              int noShowCount) {
    }
}
