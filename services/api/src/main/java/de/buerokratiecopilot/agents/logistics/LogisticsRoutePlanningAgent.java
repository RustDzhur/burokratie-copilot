package de.buerokratiecopilot.agents.logistics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code logistics-route-planning} — Tourenplanung.
 *
 * <p>Builds a day tour proposal from the stop list: stops are ordered by time window and address,
 * packed into tours by the given weight/volume capacity and given a deterministic arrival time.
 * The heuristic uses a fixed travel time between stops and a fixed service time; there is no
 * geocoding and no map service, and the report says so.</p>
 *
 * <p>The result is a CSV artifact plus a task for dispatch. The plan is a proposal —
 * the agent confirms no tour and dispatches no driver.</p>
 */
@Component
public class LogisticsRoutePlanningAgent implements Agent {

    private static final int MAX_STOPS = 500;
    private static final int MAX_PRODUCED = 50;
    private static final LocalTime DEFAULT_START = LocalTime.of(7, 30);
    private static final int TRAVEL_MINUTES = 20;
    private static final int DEFAULT_SERVICE_MINUTES = 15;

    private final AgentArtifactStore artifacts;
    private final AgentTaskFactory tasks;
    private final ObjectMapper mapper;

    public LogisticsRoutePlanningAgent(AgentArtifactStore artifacts, AgentTaskFactory tasks,
                                       ObjectMapper mapper) {
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "logistics-route-planning";
    }

    public record Stop(String orderId, String address, LocalTime windowFrom, LocalTime windowTo,
                       BigDecimal weight, BigDecimal volume, int serviceMinutes) {
    }

    public record Capacity(BigDecimal weight, BigDecimal volume) {
        public boolean unlimited() {
            return weight == null && volume == null;
        }
    }

    public record Tour(int number, List<PlannedStop> stops) {
    }

    public record PlannedStop(Stop stop, LocalTime arrival, BigDecimal loadWeight,
                              BigDecimal loadVolume) {
    }

    public record Conflict(String orderId, String code, String message) {
    }

    public record Plan(List<Tour> tours, List<Conflict> conflicts) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate deliveryDate;
        try {
            deliveryDate = LocalDate.parse(require(context.input("deliveryDate")));
        }
        catch (RuntimeException ex) {
            return AgentResult.skipped("deliveryDate im Format YYYY-MM-DD fehlt; ohne Liefertag "
                    + "keine Tourenplanung.");
        }
        List<Stop> stops;
        try {
            stops = parseStops(context.input("stops"));
        }
        catch (IllegalArgumentException ex) {
            return AgentResult.failed("stops ist kein gültiges JSON-Array: " + ex.getMessage());
        }
        if (stops.isEmpty()) {
            return AgentResult.skipped("stops fehlt oder enthält keine verwertbaren Stopps; es "
                    + "gibt keine Tour zu planen.");
        }
        if (stops.stream().allMatch(stop -> stop.address() == null && stop.orderId() == null)) {
            return AgentResult.skipped("Kein Stopp trägt Adresse oder Auftragsnummer; eine Tour "
                    + "lässt sich daraus nicht bilden.");
        }
        Capacity capacity;
        try {
            capacity = parseCapacity(context.input("vehicleCapacity"));
        }
        catch (IllegalArgumentException ex) {
            return AgentResult.failed("vehicleCapacity ist ungültig: " + ex.getMessage());
        }
        Plan plan = plan(stops, capacity, DEFAULT_START);

        String content = toCsv(deliveryDate, plan);
        AgentArtifactStore.Artifact artifact;
        try {
            artifact = artifacts.store(context.tenantId(), context.actorUserId(),
                    "route-plan:" + deliveryDate + ":" + stops.size() + ":"
                            + capacityKey(capacity) + ":" + hash(content),
                    "Tourenplan " + deliveryDate, "tourenplan-" + deliveryDate + ".csv",
                    "text/csv", "TXT", content);
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("Der Tourenplan konnte nicht gespeichert werden: "
                    + ex.getMessage());
        }

        String title = "Tourenplan prüfen: " + deliveryDate;
        AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                context.actorUserId(), title, description(deliveryDate, plan, artifact),
                plan.conflicts().isEmpty() ? Task.Priority.NORMAL : Task.Priority.HIGH,
                "Der Tourenplan ist ein Vorschlag; Disposition muss Reihenfolge, Kapazität und "
                        + "Zeitfenster bestätigen.",
                context.now().plus(2, ChronoUnit.DAYS),
                List.of("logistics", "route-planning"));

        for (Conflict conflict : plan.conflicts().stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("route-conflict", conflict.orderId(), conflict.code() + ": "
                    + conflict.message());
        }
        context.addProduced("route-plan", artifact.storageKey(),
                plan.tours().size() + " Tour(en), " + stops.size() + " Stopps");
        context.addProduced("task", task.taskId().toString(), title);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("deliveryDate", deliveryDate.toString());
        meta.put("stops", String.valueOf(stops.size()));
        meta.put("tours", String.valueOf(plan.tours().size()));
        meta.put("conflicts", String.valueOf(plan.conflicts().size()));
        meta.put("capacityWeight", capacity.weight() == null ? "" : capacity.weight().toPlainString());
        meta.put("capacityVolume", capacity.volume() == null ? "" : capacity.volume().toPlainString());
        meta.put("storageKey", artifact.storageKey());
        meta.put("artifactCreated", String.valueOf(artifact.created()));
        meta.put("taskId", task.taskId().toString());
        meta.put("taskCreated", String.valueOf(task.created()));
        meta.put("limitation", "Deterministische Heuristik ohne Geocoding/Kartendienst: feste "
                + "Fahrzeit von " + TRAVEL_MINUTES + " Min. zwischen Stopps, feste Servicezeit. "
                + "Keine Tour wird bestätigt oder an einen Fahrer gesendet.");

        return AgentResult.done("Tourenplan " + deliveryDate + ": " + stops.size() + " Stopps in "
                        + plan.tours().size() + " Tour(en), " + plan.conflicts().size()
                        + " Konflikt(e). Vorschlag gespeichert; Disposition entscheidet.",
                context.produced(), meta);
    }

    /**
     * Deterministic tour construction. Visible for tests.
     *
     * <p>Stops are sorted by window start and address. A new tour begins when the next stop would
     * exceed the vehicle capacity; the arrival time is travel time plus waiting until the window
     * opens. A stop whose arrival is after its window closes is a conflict but stays in the plan,
     * so dispatch sees the problem instead of a silently dropped delivery.</p>
     */
    public static Plan plan(List<Stop> stops, Capacity capacity, LocalTime start) {
        List<Stop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparing(Stop::windowFrom,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(stop -> stop.address() == null ? "" : stop.address()));
        List<Tour> tours = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<PlannedStop> currentStops = new ArrayList<>();
        BigDecimal loadWeight = BigDecimal.ZERO;
        BigDecimal loadVolume = BigDecimal.ZERO;
        LocalTime serviceEnd = start == null ? DEFAULT_START : start;
        int tourNumber = 1;

        for (Stop stop : sorted) {
            BigDecimal weight = stop.weight() == null ? BigDecimal.ZERO : stop.weight();
            BigDecimal volume = stop.volume() == null ? BigDecimal.ZERO : stop.volume();
            boolean exceeds = capacity != null && !capacity.unlimited()
                    && (capacity.weight() != null
                            && loadWeight.add(weight).compareTo(capacity.weight()) > 0
                        || capacity.volume() != null
                            && loadVolume.add(volume).compareTo(capacity.volume()) > 0);
            if (exceeds && !currentStops.isEmpty()) {
                tours.add(new Tour(tourNumber++, List.copyOf(currentStops)));
                currentStops = new ArrayList<>();
                loadWeight = BigDecimal.ZERO;
                loadVolume = BigDecimal.ZERO;
                serviceEnd = start == null ? DEFAULT_START : start;
            }
            if (capacity != null && !capacity.unlimited()
                    && currentStops.isEmpty() && exceeds) {
                conflicts.add(new Conflict(reference(stop), "CAPACITY_EXCEEDED",
                        "Einzelstopp überschreitet die Fahrzeugkapazität (Gewicht "
                                + weight.toPlainString() + ", Volumen "
                                + volume.toPlainString() + ")"));
            }
            loadWeight = loadWeight.add(weight);
            loadVolume = loadVolume.add(volume);
            LocalTime arrival = serviceEnd.plusMinutes(TRAVEL_MINUTES);
            LocalTime windowFrom = stop.windowFrom();
            LocalTime windowTo = stop.windowTo();
            if (windowFrom != null && arrival.isBefore(windowFrom)) {
                arrival = windowFrom;
            }
            if (windowTo != null && arrival.isAfter(windowTo)) {
                conflicts.add(new Conflict(reference(stop), "WINDOW_MISSED",
                        "Ankunft " + arrival + " liegt nach dem Zeitfenster bis " + windowTo));
            }
            if (stop.address() == null) {
                conflicts.add(new Conflict(reference(stop), "ADDRESS_MISSING",
                        "Stopp ohne Lieferadresse"));
            }
            int service = stop.serviceMinutes() > 0 ? stop.serviceMinutes()
                    : DEFAULT_SERVICE_MINUTES;
            serviceEnd = arrival.plusMinutes(service);
            currentStops.add(new PlannedStop(stop, arrival, loadWeight, loadVolume));
        }
        if (!currentStops.isEmpty()) {
            tours.add(new Tour(tourNumber, List.copyOf(currentStops)));
        }
        return new Plan(List.copyOf(tours), List.copyOf(conflicts));
    }

    private List<Stop> parseStops(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows;
        try {
            rows = mapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        List<Stop> stops = new ArrayList<>();
        for (Map<String, Object> row : rows.stream().limit(MAX_STOPS).toList()) {
            stops.add(new Stop(cleanObject(value(row, "orderId", "order_id", "id", "auftrag")),
                    cleanObject(value(row, "address", "adresse", "deliveryAddress")),
                    time(value(row, "timeFrom", "time_from", "windowFrom", "from")),
                    time(value(row, "timeTo", "time_to", "windowTo", "to")),
                    decimal(value(row, "weight", "gewicht")),
                    decimal(value(row, "volume", "volumen")),
                    integer(value(row, "serviceMinutes", "service_minutes", "service"))));
        }
        return stops;
    }

    private Capacity parseCapacity(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Capacity(null, null);
        }
        try {
            Map<String, Object> map = mapper.readValue(raw,
                    new TypeReference<Map<String, Object>>() {
                    });
            return new Capacity(decimal(value(map, "weight", "maxWeight")),
                    decimal(value(map, "volume", "maxVolume")));
        }
        catch (Exception ignored) {
            BigDecimal weight = decimal(raw);
            if (weight == null) {
                throw new IllegalArgumentException("weder Zahl noch JSON-Objekt");
            }
            return new Capacity(weight, null);
        }
    }

    private static String toCsv(LocalDate date, Plan plan) {
        StringBuilder csv = new StringBuilder(
                "date,tour,sequence,order_id,address,window_from,window_to,arrival,"
                        + "load_weight,load_volume,status\n");
        for (Tour tour : plan.tours()) {
            int sequence = 1;
            for (PlannedStop planned : tour.stops()) {
                Stop stop = planned.stop();
                String status = plan.conflicts().stream()
                        .anyMatch(conflict -> conflict.orderId().equals(reference(stop)))
                        ? "CONFLICT" : "OK";
                csv.append(date).append(',').append(tour.number()).append(',').append(sequence++)
                        .append(',').append(csv(reference(stop))).append(',')
                        .append(csv(stop.address())).append(',')
                        .append(stop.windowFrom() == null ? "" : stop.windowFrom()).append(',')
                        .append(stop.windowTo() == null ? "" : stop.windowTo()).append(',')
                        .append(planned.arrival()).append(',')
                        .append(planned.loadWeight().toPlainString()).append(',')
                        .append(planned.loadVolume().toPlainString()).append(',').append(status)
                        .append('\n');
            }
        }
        return csv.toString();
    }

    private static String description(LocalDate date, Plan plan,
                                      AgentArtifactStore.Artifact artifact) {
        StringBuilder text = new StringBuilder();
        text.append("Tourenplan ").append(date).append(" (Vorschlag, nicht bestätigt)\n")
                .append("Touren: ").append(plan.tours().size()).append('\n')
                .append("Ablage: ").append(artifact.storageKey()).append("\n\n")
                .append("Konflikte:\n");
        if (plan.conflicts().isEmpty()) {
            text.append("- keine\n");
        }
        else {
            for (Conflict conflict : plan.conflicts().stream().limit(MAX_PRODUCED).toList()) {
                text.append("- [").append(conflict.code()).append("] ")
                        .append(conflict.orderId()).append(": ").append(conflict.message())
                        .append('\n');
            }
        }
        text.append("\nBitte Reihenfolge, Kapazität und Zeitfenster prüfen und die Tour ")
                .append("freigeben. Es wurde nichts an einen Fahrer gesendet.");
        return text.toString();
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 16);
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String capacityKey(Capacity capacity) {
        return (capacity.weight() == null ? "-" : capacity.weight().toPlainString()) + "/"
                + (capacity.volume() == null ? "-" : capacity.volume().toPlainString());
    }

    private static Object value(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String reference(Stop stop) {
        if (stop.orderId() != null) {
            return stop.orderId();
        }
        return stop.address() == null ? "(ohne Referenz)" : stop.address();
    }

    private static LocalTime time(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).strip();
        try {
            return LocalTime.parse(text);
        }
        catch (RuntimeException ignored) {
            try {
                return LocalTime.parse(text, java.time.format.DateTimeFormatter.ofPattern("H:mm"));
            }
            catch (RuntimeException ex) {
                return null;
            }
        }
    }

    private static int integer(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? 0 : Integer.parseInt(String.valueOf(raw).strip());
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static BigDecimal decimal(Object raw) {
        if (raw instanceof BigDecimal value) {
            return value;
        }
        if (raw instanceof Number value) {
            return new BigDecimal(value.toString());
        }
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(raw).replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String cleanObject(Object value) {
        return value == null ? null : clean(String.valueOf(value));
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        return value.strip();
    }
}
