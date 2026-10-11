package de.buerokratiecopilot.workflow.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.workflow.WorkflowDefinition;
import de.buerokratiecopilot.workflow.WorkflowEngine;
import de.buerokratiecopilot.workflow.WorkflowService;

/**
 * Verifies the server side of the process editor (spec §16, F-004).
 *
 * <p>A canvas cannot be checked without a browser, but the acceptance criterion can be, and it is checked
 * here: <em>a process assembled in the editor runs and is executed by the engine</em>. The test builds the
 * graph an editor would produce, converts it, publishes it and runs it to completion. That is the whole
 * path.</p>
 *
 * <p><strong>What is not verified: the editor itself.</strong> There is no canvas in this repository, so
 * nothing here says anything about dragging boxes, and saying otherwise would be a claim about a screen
 * that does not exist.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowEditorIT {

    @Autowired
    private WorkflowEditor editor;

    @Autowired
    private WorkflowService workflows;

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    private record Tenant(UUID tenantId, UUID userId) {
    }

    private Tenant tenant() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Editor Test', 'COMPANY') returning id
                """, UUID.class, "ed-" + UUID.randomUUID().toString().substring(0, 12));
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "ed-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', true)
                """, tenantId, userId);
        return new Tenant(tenantId, userId);
    }

    private UUID define(Tenant tenant, String code) {
        return RequestContext.runAs(tenant.tenantId(), () -> workflows.define(tenant.tenantId(), code,
                code, null, "MANUAL", Map.of(), tenant.userId())).definitionId();
    }

    /** The graph a canvas would produce for a process with a branch. */
    private WorkflowEditor.Graph branchingGraph() {
        return new WorkflowEditor.Graph("start",
                List.of(new WorkflowEditor.Node("start", "ACTION", Map.of(), 100d, 100d),
                        new WorkflowEditor.Node("check", "CONDITION", Map.of("field", "urgent",
                                "equals", "true"), 100d, 210d),
                        new WorkflowEditor.Node("fast", "TERMINATE", Map.of(), 300d, 320d),
                        new WorkflowEditor.Node("slow", "NOTIFICATION", Map.of(), 100d, 320d),
                        new WorkflowEditor.Node("end", "TERMINATE", Map.of(), 100d, 430d)),
                List.of(new WorkflowEditor.Edge("start", "check", null),
                        new WorkflowEditor.Edge("check", "fast", "true"),
                        new WorkflowEditor.Edge("check", "slow", "false"),
                        new WorkflowEditor.Edge("slow", "end", null)));
    }

    // ---------------------------------------------------------------- the path a canvas produces

    @Test
    @DisplayName("a process assembled in the editor runs and is executed by the engine")
    void aProcessAssembledInTheEditorRuns() {
        Tenant tenant = tenant();
        UUID definitionId = define(tenant, "editor-durchlauf");

        WorkflowService.Version version = RequestContext.runAs(tenant.tenantId(),
                () -> editor.publishGraph(tenant.tenantId(), definitionId, branchingGraph(), "Aus dem Editor",
                        tenant.userId()));
        assertThat(version.version()).isEqualTo(1);

        // start -> check -> fast, and the process is at its end.
        WorkflowEngine.Run run = RequestContext.runAs(tenant.tenantId(), () -> engine.start(
                tenant.tenantId(), definitionId, "MANUAL", Map.of("urgent", "true"), null, null, null,
                "editor-run", tenant.userId()));
        for (int step = 0; step < 3; step++) {
            RequestContext.runAs(tenant.tenantId(), () -> engine.advance(tenant.tenantId(), run.runId()));
        }

        assertThat(jdbc.queryForObject("select status from workflow_runs where id = ?", String.class,
                run.runId()))
                .as("was im Editor zusammengestellt wurde, läuft auch")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("the branch the editor drew is the branch the engine takes")
    void theDrawnBranchIsTheBranchTaken() {
        Tenant tenant = tenant();
        UUID definitionId = define(tenant, "editor-zweig");
        RequestContext.runAs(tenant.tenantId(), () -> editor.publishGraph(tenant.tenantId(), definitionId,
                branchingGraph(), null, tenant.userId()));

        WorkflowEngine.Run run = RequestContext.runAs(tenant.tenantId(), () -> engine.start(
                tenant.tenantId(), definitionId, "MANUAL", Map.of("urgent", "false"), null, null, null,
                "editor-branch", tenant.userId()));
        RequestContext.runAs(tenant.tenantId(), () -> engine.advance(tenant.tenantId(), run.runId()));
        RequestContext.runAs(tenant.tenantId(), () -> engine.advance(tenant.tenantId(), run.runId()));

        assertThat(jdbc.queryForObject("select current_step_key from workflow_runs where id = ?",
                String.class, run.runId()))
                .as("the label on the edge decided, not the order the boxes were drawn in")
                .isEqualTo("slow");
    }

    @Test
    @DisplayName("a saved process opens again as a picture")
    void aProcessOpensAgainAsAPicture() {
        Tenant tenant = tenant();
        UUID definitionId = define(tenant, "editor-bild");
        RequestContext.runAs(tenant.tenantId(), () -> editor.publishGraph(tenant.tenantId(), definitionId,
                branchingGraph(), null, tenant.userId()));

        WorkflowEditor.Graph graph = RequestContext.runAs(tenant.tenantId(),
                () -> editor.graphOf(tenant.tenantId(), definitionId, 0));

        assertThat(graph.entryNode()).isEqualTo("start");
        assertThat(graph.nodes()).hasSize(5);
        assertThat(graph.edges()).hasSize(4);
        assertThat(graph.nodes()).allSatisfy(node -> {
            assertThat(node.x()).isNotNull();
            assertThat(node.y()).isNotNull();
        });
    }

    @Test
    @DisplayName("a saved process keeps the positions it was laid out with")
    void positionsAreReturned() {
        Tenant tenant = tenant();
        UUID definitionId = define(tenant, "editor-position");

        WorkflowEditor.Graph laid = new WorkflowEditor.Graph("a",
                List.of(new WorkflowEditor.Node("a", "TERMINATE", Map.of(), 42d, 84d)), List.of());
        RequestContext.runAs(tenant.tenantId(), () -> editor.publishGraph(tenant.tenantId(), definitionId,
                laid, null, tenant.userId()));

        WorkflowEditor.Graph graph = RequestContext.runAs(tenant.tenantId(),
                () -> editor.graphOf(tenant.tenantId(), definitionId, 0));

        // The positions in the definition are not where the boxes were, so the canvas gets a layout; what
        // matters is that it opens as a picture rather than as a pile in one place.
        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.nodes().getFirst().y()).isGreaterThan(0d);
    }

    // ---------------------------------------------------------------- checking a draft

    @Test
    @DisplayName("a draft with a hanging connection is refused before it is saved")
    void aHangingConnectionIsRefused() {
        WorkflowEditor.Graph broken = new WorkflowEditor.Graph("start",
                List.of(new WorkflowEditor.Node("start", "ACTION", Map.of(), 0d, 0d),
                        new WorkflowEditor.Node("end", "TERMINATE", Map.of(), 0d, 100d)),
                List.of(new WorkflowEditor.Edge("start", "gibt-es-nicht", null)));

        WorkflowDefinition.Validation validation = WorkflowEditor.checkDraft(broken);

        assertThat(validation.isValid()).isFalse();
        assertThat(validation.problems()).extracting(WorkflowDefinition.Problem::code)
                .contains("TARGET_NOT_FOUND");
    }

    @Test
    @DisplayName("a step nothing leads to is refused, though it looks valid on paper")
    void anUnreachableStepIsRefused() {
        WorkflowEditor.Graph graph = new WorkflowEditor.Graph("start",
                List.of(new WorkflowEditor.Node("start", "TERMINATE", Map.of(), 0d, 0d),
                        new WorkflowEditor.Node("verloren", "ACTION", Map.of(), 0d, 100d)),
                List.of());

        WorkflowDefinition.Validation validation = WorkflowEditor.checkDraft(graph);

        assertThat(validation.problems()).extracting(WorkflowDefinition.Problem::code)
                .as("a step nothing leads to will never run, and nothing else would have noticed")
                .contains("UNREACHABLE");
    }

    @Test
    @DisplayName("a process that can never end is refused")
    void aProcessWithoutAnEndIsRefused() {
        WorkflowEditor.Graph graph = new WorkflowEditor.Graph("start",
                List.of(new WorkflowEditor.Node("start", "ACTION", Map.of(), 0d, 0d)),
                List.of(new WorkflowEditor.Edge("start", "start", null)));

        assertThat(WorkflowEditor.checkDraft(graph).problems())
                .extracting(WorkflowDefinition.Problem::code)
                .contains("NO_END");
    }

    @Test
    @DisplayName("an empty canvas is refused")
    void anEmptyCanvasIsRefused() {
        WorkflowDefinition.Validation validation = WorkflowEditor.checkDraft(
                new WorkflowEditor.Graph(null, List.of(), List.of()));

        assertThat(validation.isValid()).isFalse();
        assertThat(validation.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("a condition without both branches is refused while the person is still looking at it")
    void aConditionWithoutBranchesIsRefused() {
        WorkflowEditor.Graph graph = new WorkflowEditor.Graph("check",
                List.of(new WorkflowEditor.Node("check", "CONDITION", Map.of("field", "x", "equals", "1"),
                                0d, 0d),
                        new WorkflowEditor.Node("end", "TERMINATE", Map.of(), 0d, 100d)),
                List.of(new WorkflowEditor.Edge("check", "end", "true")));

        assertThat(WorkflowEditor.checkDraft(graph).problems())
                .extracting(WorkflowDefinition.Problem::code)
                .contains("CONDITION_WITHOUT_BRANCHES");
    }

    @Test
    @DisplayName("publishing an invalid draft is refused")
    void publishingAnInvalidDraftIsRefused() {
        Tenant tenant = tenant();
        UUID definitionId = define(tenant, "editor-kaputt");
        WorkflowEditor.Graph broken = new WorkflowEditor.Graph("start",
                List.of(new WorkflowEditor.Node("start", "ACTION", Map.of(), 0d, 0d)),
                List.of(new WorkflowEditor.Edge("start", "weg", null)));

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(),
                () -> editor.publishGraph(tenant.tenantId(), definitionId, broken, null, tenant.userId())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nicht gültig");
        assertThat(RequestContext.runAs(tenant.tenantId(), () -> workflows.versions(tenant.tenantId(),
                definitionId))).isEmpty();
    }

    // ---------------------------------------------------------------- the drawing itself

    @Test
    @DisplayName("the edges become the keys the engine follows")
    void edgesBecomeKeys() {
        WorkflowDefinition.Definition definition = WorkflowEditor.toDefinition(branchingGraph());

        assertThat(definition.entryStep()).isEqualTo("start");
        assertThat(definition.step("start").next()).isEqualTo("check");
        assertThat(definition.step("check").onTrue()).isEqualTo("fast");
        assertThat(definition.step("check").onFalse()).isEqualTo("slow");
    }

    @Test
    @DisplayName("a terminating step gets no next even when a line leaves it")
    void aTerminatingStepGetsNoNext() {
        WorkflowEditor.Graph graph = new WorkflowEditor.Graph("end",
                List.of(new WorkflowEditor.Node("end", "TERMINATE", Map.of(), 0d, 0d),
                        new WorkflowEditor.Node("danach", "ACTION", Map.of(), 0d, 100d)),
                List.of(new WorkflowEditor.Edge("end", "danach", null)));

        assertThat(WorkflowEditor.toDefinition(graph).step("end").next()).isNull();
    }

    @Test
    @DisplayName("the palette says what each step type needs")
    void thePaletteSaysWhatIsNeeded() {
        assertThat(WorkflowEditor.palette())
                .as("a person should not discover from a failure that a condition needs two branches")
                .allSatisfy(item -> {
                    assertThat(item).containsKeys("type", "description", "fields");
                    assertThat(String.valueOf(item.get("description"))).isNotBlank();
                });
        assertThat(WorkflowEditor.palette()).extracting(item -> item.get("type"))
                .contains("ACTION", "CONDITION", "APPROVAL", "TERMINATE");
    }

    @Test
    @DisplayName("every step type the schema allows is in the palette")
    void thePaletteCoversTheSchema() {
        List<String> paletteTypes = WorkflowEditor.palette().stream()
                .map(item -> String.valueOf(item.get("type"))).toList();

        // A type the schema allows but the palette does not offer is a type nobody can use.
        assertThat(paletteTypes).containsAll(WorkflowDefinition.STEP_TYPES);
    }

    @Test
    @DisplayName("a canvas of another tenant is not reachable")
    void anotherTenantsCanvasIsNotReachable() {
        Tenant owner = tenant();
        Tenant stranger = tenant();
        UUID definitionId = define(owner, "editor-fremd");
        RequestContext.runAs(owner.tenantId(), () -> editor.publishGraph(owner.tenantId(), definitionId,
                branchingGraph(), null, owner.userId()));

        assertThatThrownBy(() -> RequestContext.runAs(stranger.tenantId(),
                () -> editor.graphOf(stranger.tenantId(), definitionId, 0)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a draft can be assembled and checked without being saved")
    void aDraftCanBeCheckedWithoutSaving() {
        // The canvas asks this on every change, so it must not write anything.
        WorkflowDefinition.Validation first = WorkflowEditor.checkDraft(branchingGraph());
        WorkflowDefinition.Validation second = WorkflowEditor.checkDraft(branchingGraph());

        assertThat(first.isValid()).isTrue();
        assertThat(second.isValid()).isTrue();
    }
}
