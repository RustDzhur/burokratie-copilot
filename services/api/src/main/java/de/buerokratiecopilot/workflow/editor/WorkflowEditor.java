package de.buerokratiecopilot.workflow.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.workflow.WorkflowDefinition;
import de.buerokratiecopilot.workflow.WorkflowService;

/**
 * The server side of the process editor (spec §16, F-004).
 *
 * <p>A canvas cannot be verified without a browser, but the acceptance criterion can: <em>a process
 * assembled in the editor runs and is executed by the engine.</em> That is a statement about the path from
 * what the editor produces to what the engine consumes, and it is checked here end to end.</p>
 *
 * <p><strong>The editor works with nodes and edges; the engine works with steps and keys.</strong> Keeping
 * them apart matters. A canvas stores where a box was dragged to, which is a fact about somebody's screen
 * and not about the process; the definition stores what happens, which is the only thing a run should
 * depend on. Converting in one place means the two can never drift.</p>
 *
 * <p><strong>A draft is checked before it is saved.</strong> Somebody dragging boxes is going to leave a
 * connection hanging, and finding out at that moment is far better than finding out when an invoice is
 * halfway through — which is the same reason a published version is validated, applied one step earlier.</p>
 *
 * <p><strong>What the palette offers says what each step needs.</strong> A person assembling a process
 * should not have to discover from a failure that an approval step needs a next step and a condition needs
 * both branches.</p>
 */
@Service
public class WorkflowEditor {

    private final JdbcTemplate jdbc;
    private final WorkflowService workflows;

    public WorkflowEditor(JdbcTemplate jdbc, WorkflowService workflows) {
        this.jdbc = jdbc;
        this.workflows = workflows;
    }

    /**
     * What the palette shows.
     *
     * <p>Each entry names the step type, what it does in one sentence, and the fields it needs.</p>
     */
    public static List<Map<String, Object>> palette() {
        return List.of(
                entry("ACTION", "Führt einen Schritt aus", List.of("next")),
                entry("CONDITION", "Verzweigt nach einer Bedingung", List.of("field", "equals", "onTrue",
                        "onFalse")),
                entry("APPROVAL", "Wartet auf die Freigabe durch eine Person", List.of("next")),
                entry("DELAY", "Wartet eine feste Zeit", List.of("seconds", "next")),
                entry("WAIT", "Wartet auf ein Ereignis", List.of("waitFor", "next")),
                entry("TASK_CREATE", "Legt eine Aufgabe an", List.of("next")),
                entry("NOTIFICATION", "Benachrichtigt jemanden", List.of("next")),
                entry("PARALLEL", "Führt Zweige gleichzeitig aus", List.of("next")),
                entry("INTEGRATION_CALL", "Ruft eine Anbindung auf", List.of("next")),
                entry("SUB_WORKFLOW", "Startet einen anderen Prozess", List.of("next")),
                entry("TERMINATE", "Beendet den Prozess", List.of()));
    }

    private static Map<String, Object> entry(String type, String description, List<String> fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("description", description);
        map.put("fields", fields);
        return map;
    }

    /**
     * What a canvas draws: nodes with positions and edges between them.
     *
     * <p>Positions are stored and returned, because a person who laid a process out expects to find it laid
     * out again. They are kept out of the definition, because where a box sits says nothing about what the
     * process does.</p>
     */
    public record Graph(String entryNode, List<Node> nodes, List<Edge> edges) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("entryNode", entryNode);
            map.put("nodes", nodes.stream().map(Node::asMap).toList());
            map.put("edges", edges.stream().map(Edge::asMap).toList());
            return map;
        }
    }

    /** A box on the canvas. */
    public record Node(String key, String type, Map<String, Object> config, Double x, Double y) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("type", type);
            map.put("config", config == null ? Map.of() : config);
            map.put("x", x == null ? 0d : x);
            map.put("y", y == null ? 0d : y);
            return map;
        }
    }

    /** A line between two boxes. */
    public record Edge(String from, String to, String label) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("from", from);
            map.put("to", to);
            map.put("label", label == null ? "" : label);
            return map;
        }
    }

    /** The editor's own view of a definition. */
    @Transactional(readOnly = true)
    public Graph graphOf(UUID tenantId, UUID definitionId, int version) {
        WorkflowService.Version current = workflows.currentVersion(tenantId, definitionId);
        int wanted = version <= 0 ? current.version() : version;

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, definition::text as definition from workflow_versions
                where definition_id = ? and tenant_id = ? and version = ?
                """, definitionId, tenantId, wanted);
        if (rows.isEmpty()) {
            throw ApiException.notFound("workflow version");
        }

        WorkflowDefinition.Definition definition =
                WorkflowDefinition.parse(WorkflowService.readJson((String) rows.getFirst().get("definition")));
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        // A simple column layout, so a process that was only ever stored as data still opens as a picture
        // rather than as a pile of boxes in one place.
        int row = 0;
        for (WorkflowDefinition.Step step : definition.steps()) {
            nodes.add(new Node(step.key(), step.type(), step.config(), 120d, 80d + 110d * row++));
            for (Edge edge : edgesOf(step)) {
                edges.add(edge);
            }
        }
        return new Graph(definition.entryStep(), List.copyOf(nodes), List.copyOf(edges));
    }

    private List<Edge> edgesOf(WorkflowDefinition.Step step) {
        List<Edge> edges = new ArrayList<>();
        if (step.next() != null) {
            edges.add(new Edge(step.key(), step.next(), null));
        }
        if (step.onTrue() != null) {
            edges.add(new Edge(step.key(), step.onTrue(), "true"));
        }
        if (step.onFalse() != null) {
            edges.add(new Edge(step.key(), step.onFalse(), "false"));
        }
        return edges;
    }

    /**
     * Turns a canvas into a definition.
     *
     * <p>The edges become the keys each step leads to, which is what the engine reads. A condition gets its
     * two branches from the labels on its outgoing edges, so a person draws the branch rather than typing
     * which one is which.</p>
     */
    public static WorkflowDefinition.Definition toDefinition(Graph graph) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Prozess enthält keine Schritte");
        }
        Map<String, List<Edge>> outgoing = new LinkedHashMap<>();
        for (Edge edge : graph.edges() == null ? List.<Edge>of() : graph.edges()) {
            outgoing.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge);
        }

        List<WorkflowDefinition.Step> steps = new ArrayList<>();
        for (Node node : graph.nodes()) {
            List<Edge> edges = outgoing.getOrDefault(node.key(), List.of());
            Map<String, Object> config = new LinkedHashMap<>(node.config() == null ? Map.of() : node.config());

            if ("CONDITION".equals(node.type())) {
                // The labels on the two outgoing edges say which branch is which; without them the order
                // would decide, and the order is whatever the person happened to draw first.
                for (Edge edge : edges) {
                    if ("true".equalsIgnoreCase(edge.label())) {
                        config.put("onTrue", edge.to());
                    }
                    else if ("false".equalsIgnoreCase(edge.label())) {
                        config.put("onFalse", edge.to());
                    }
                }
            }
            else if (!edges.isEmpty() && !"TERMINATE".equals(node.type())) {
                config.put("next", edges.getFirst().to());
            }
            steps.add(new WorkflowDefinition.Step(node.key(), node.type(), config));
        }
        return new WorkflowDefinition.Definition(graph.entryNode(), List.copyOf(steps));
    }

    /**
     * Checks a draft without saving it.
     *
     * <p>Somebody dragging boxes is going to leave a connection hanging, and finding that out while they are
     * still looking at the canvas is far better than finding it out when an invoice is halfway through.</p>
     */
    public static WorkflowDefinition.Validation checkDraft(Graph graph) {
        WorkflowDefinition.Validation structural;
        try {
            structural = WorkflowDefinition.validate(toDefinition(graph));
        }
        catch (ApiException ex) {
            return new WorkflowDefinition.Validation(
                    List.of(new WorkflowDefinition.Problem("EMPTY", ex.getMessage())));
        }

        List<WorkflowDefinition.Problem> problems = new ArrayList<>(structural.problems());
        Set<String> keys = new LinkedHashSet<>();
        if (graph.nodes() != null) {
            graph.nodes().forEach(node -> keys.add(node.key()));
        }

        // Reachability, which the definition check cannot see: a step nothing leads to is a step that will
        // never run, and it looks perfectly valid on paper.
        Set<String> reachable = new LinkedHashSet<>();
        collectReachable(graph, graph.entryNode(), reachable);
        for (String key : keys) {
            if (!reachable.contains(key)) {
                problems.add(new WorkflowDefinition.Problem("UNREACHABLE",
                        "Der Schritt '" + key + "' ist von keinem anderen erreichbar und wird nie ausgeführt"));
            }
        }

        // A process that cannot end would run until somebody notices.
        boolean canEnd = graph.nodes() != null && graph.nodes().stream()
                .anyMatch(node -> "TERMINATE".equals(node.type()));
        if (!canEnd) {
            problems.add(new WorkflowDefinition.Problem("NO_END",
                    "Der Prozess hat keinen Schritt, der ihn beendet"));
        }

        return new WorkflowDefinition.Validation(List.copyOf(problems));
    }

    private static void collectReachable(Graph graph, String key, Set<String> reachable) {
        if (key == null || !reachable.add(key)) {
            return;
        }
        for (Edge edge : graph.edges() == null ? List.<Edge>of() : graph.edges()) {
            if (edge.from().equals(key)) {
                collectReachable(graph, edge.to(), reachable);
            }
        }
    }

    /**
     * Saves and publishes what was assembled, and hands back the version.
     *
     * <p>Refused when the draft does not check out, so a canvas cannot publish a process that will not
     * run.</p>
     */
    @Transactional
    public WorkflowService.Version publishGraph(UUID tenantId, UUID definitionId, Graph graph,
                                                String changeNote, UUID publishedBy) {
        WorkflowDefinition.Validation validation = checkDraft(graph);
        if (!validation.isValid()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Prozess ist nicht gültig und wird nicht veröffentlicht. " + validation.summary());
        }
        // Published as plain JSON, not as the record: publish() round-trips what it is given through the
        // database, and a record did not survive that trip - it came back as a process with no steps. The
        // validation below refused it, which is how it was found, but a definition that only works when it
        // is not stored is not a definition.
        return workflows.publish(tenantId, definitionId, toJson(toDefinition(graph)), Map.of(),
                changeNote, publishedBy);
    }

    /** A definition as plain JSON, which is what publishing stores and reads back. */
    static Map<String, Object> toJson(WorkflowDefinition.Definition definition) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (WorkflowDefinition.Step step : definition.steps()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", step.key());
            entry.put("type", step.type());
            entry.put("config", step.config() == null ? Map.of() : step.config());
            steps.add(entry);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("entryStep", definition.entryStep());
        json.put("steps", steps);
        return json;
    }

    /** What the canvas would show before anything is saved, for a person starting from a template. */
    @Transactional(readOnly = true)
    public Graph draftFrom(UUID tenantId, UUID definitionId) {
        WorkflowService.Version current = workflows.currentVersion(tenantId, definitionId);
        return graphOf(tenantId, definitionId, current.version());
    }
}
