package de.buerokratiecopilot.workflow.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.workflow.PresetProcesses;
import de.buerokratiecopilot.workflow.WorkflowDefinition;
import de.buerokratiecopilot.workflow.WorkflowEngine;
import de.buerokratiecopilot.workflow.WorkflowRunService;
import de.buerokratiecopilot.workflow.WorkflowService;
import de.buerokratiecopilot.workflow.editor.WorkflowEditor;

/**
 * Processes: definitions, the engine, the editor and the presets (spec §16, §20; F-001 to F-005).
 *
 * <p>The entire workflow surface was implemented and tested with no endpoint. F-004's acceptance criterion
 * — a process assembled in the editor runs and is executed by the engine — was verified through the
 * services, which is a true statement about the code and not one about anybody being able to use it.</p>
 *
 * <p><strong>A published version is never edited, and the endpoints do not offer to.</strong> Publishing is
 * a separate operation from drafting, and there is no route that changes a version in place: an instance
 * that started on version three finishes on version three, and an endpoint that could rewrite it would make
 * that unanswerable.</p>
 *
 * <p><strong>The engine is driven the way a machine drives it.</strong> Starting is idempotent by key, and
 * a delivered event may arrive twice; both are properties the services already have, and the endpoints pass
 * the caller's values through rather than inventing defaults for them.</p>
 *
 * <p>The permissions are the catalogue's own — {@code workflows.view}, {@code workflows.create},
 * {@code workflows.edit}, {@code workflows.publish}, {@code workflows.execute}, {@code workflows.cancel} —
 * checked before being written.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Workflows", description = "Process definitions, runs and the editor")
public class WorkflowController {

    private static final String SEE = "hasAnyAuthority('workflows.view','workflows.edit','workflows.publish')";
    private static final String DEFINE = "hasAnyAuthority('workflows.create','workflows.edit')";
    private static final String PUBLISH = "hasAnyAuthority('workflows.publish','workflows.edit')";
    private static final String RUN = "hasAnyAuthority('workflows.run','workflows.execute','workflows.edit')";

    private final WorkflowService workflows;
    private final WorkflowEngine engine;
    private final WorkflowRunService runs;
    private final WorkflowEditor editor;
    private final PresetProcesses presets;

    public WorkflowController(WorkflowService workflows, WorkflowEngine engine, WorkflowRunService runs,
                              WorkflowEditor editor, PresetProcesses presets) {
        this.workflows = workflows;
        this.engine = engine;
        this.runs = runs;
        this.editor = editor;
        this.presets = presets;
    }

    /** A definition as a caller supplies it. */
    public record DefineBody(String code, String name, String description, String category,
                             Map<String, Object> triggerConfig) {
    }

    /** A version to publish. */
    public record PublishBody(Object definition, Object inputSchema, String changeNote) {
    }

    /** A run to start. */
    public record StartBody(String triggerType, Map<String, Object> triggerPayload, String subjectType,
                            UUID subjectId, UUID caseId, String idempotencyKey) {
    }

    /** An event for a run that is waiting for one. */
    public record EventBody(String eventName, Map<String, Object> payload) {
    }

    /** A decision on a run that is waiting for a person. */
    public record ApprovalBody(Boolean accepted, String note) {
    }

    /** The answer to the pause of a run: move on, retry the step, or skip it. */
    public record ResumeBody(String decision, Map<String, Object> inputs) {
    }

    /** What the palette offers, so an editor does not hard-code the step types. */
    @GetMapping("/workflows/palette")
    @PreAuthorize(SEE)
    @Operation(summary = "The step types an editor may offer, and what each needs")
    public List<Map<String, Object>> palette() {
        return WorkflowEditor.palette();
    }

    /** The processes of the account. */
    @GetMapping("/workflows")
    @PreAuthorize(SEE)
    @Operation(summary = "The processes of this account")
    public List<Map<String, Object>> list() {
        return workflows.list(tenant());
    }

    /** Registers a process, or returns the one that already has the code. */
    @PostMapping("/workflows")
    @PreAuthorize(DEFINE)
    @Operation(summary = "Registers a process; the same code returns the one that exists")
    public ResponseEntity<Map<String, Object>> define(@RequestBody DefineBody body) {
        UUID tenantId = tenant();
        WorkflowService.Workflow defined = workflows.define(tenantId, body.code(), body.name(),
                body.description(), body.category(), body.triggerConfig(), CurrentUser.required());
        return ResponseEntity.status(defined.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(defined.asMap());
    }

    /**
     * Publishes a version.
     *
     * <p>Validated on the way in: a step that leads nowhere or a condition without branches is refused here,
     * while the author is still looking at it, rather than when an invoice is halfway through.</p>
     */
    @PostMapping("/workflows/{definitionId}/versions")
    @PreAuthorize(PUBLISH)
    @Operation(summary = "Publishes a version; an invalid definition is refused with every problem")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable UUID definitionId,
                                                       @RequestBody PublishBody body) {
        UUID tenantId = tenant();
        WorkflowService.Version published = workflows.publish(tenantId, definitionId, body.definition(),
                body.inputSchema(), body.changeNote(), CurrentUser.required());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("versionId",
                published.versionId().toString(), "version", published.version(),
                "status", published.status()));
    }

    /** Saves a draft, which no run may use. */
    @PostMapping("/workflows/{definitionId}/drafts")
    @PreAuthorize(DEFINE)
    @Operation(summary = "Saves a draft, which no run uses")
    public ResponseEntity<Map<String, Object>> saveDraft(@PathVariable UUID definitionId,
                                                         @RequestBody PublishBody body) {
        UUID tenantId = tenant();
        WorkflowService.Version draft = workflows.saveDraft(tenantId, definitionId, body.definition(),
                CurrentUser.required());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("versionId",
                draft.versionId().toString(), "version", draft.version(), "status", draft.status()));
    }

    /** The versions of a process, newest first. */
    @GetMapping("/workflows/{definitionId}/versions")
    @PreAuthorize(SEE)
    @Operation(summary = "The versions of a process")
    public List<Map<String, Object>> versions(@PathVariable UUID definitionId) {
        return workflows.versions(tenant(), definitionId);
    }

    /** Switches a process off; runs that are already going continue on their version. */
    @PostMapping("/workflows/{definitionId}/deactivate")
    @PreAuthorize(DEFINE)
    @Operation(summary = "Switches a process off; runs already going continue")
    public ResponseEntity<Void> deactivate(@PathVariable UUID definitionId) {
        workflows.deactivate(tenant(), definitionId);
        return ResponseEntity.noContent().build();
    }

    /** The definition as a picture: nodes with positions and the edges between them. */
    @GetMapping("/workflows/{definitionId}/graph")
    @PreAuthorize(SEE)
    @Operation(summary = "The process as nodes and edges, for an editor")
    public Map<String, Object> graph(@PathVariable UUID definitionId,
                                     @RequestParam(defaultValue = "0") int version) {
        return editor.graphOf(tenant(), definitionId, version).asMap();
    }

    /**
     * Checks what an editor has assembled without saving it.
     *
     * <p>The editor asks this on every change, so it must not write anything — and it sees what the
     * definition check cannot: a step nothing leads to looks valid on paper and will never run.</p>
     */
    @PostMapping("/workflows/{definitionId}/graph/check")
    @PreAuthorize(DEFINE)
    @Operation(summary = "Checks an assembled process without saving it")
    public WorkflowDefinition.Validation checkGraph(@PathVariable UUID definitionId,
                                                    @RequestBody WorkflowEditor.Graph graph) {
        return WorkflowEditor.checkDraft(graph);
    }

    /**
     * Publishes what the editor assembled.
     *
     * <p>This is F-004's acceptance criterion: a process assembled in an editor becomes one the engine runs.
     * The route exists so that the criterion is reachable, and the test that proves it goes through here.</p>
     */
    @PostMapping("/workflows/{definitionId}/graph/publish")
    @PreAuthorize(PUBLISH)
    @Operation(summary = "Publishes an assembled process; the engine runs what comes out")
    public ResponseEntity<Map<String, Object>> publishGraph(@PathVariable UUID definitionId,
                                                            @RequestBody WorkflowEditor.Graph graph,
                                                            @RequestParam(required = false) String note) {
        UUID tenantId = tenant();
        WorkflowService.Version published = editor.publishGraph(tenantId, definitionId, graph, note,
                CurrentUser.required());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("versionId",
                published.versionId().toString(), "version", published.version()));
    }

    /** The presets the specification requires, so an account can start from them. */
    @GetMapping("/workflows/presets")
    @PreAuthorize(SEE)
    @Operation(summary = "The processes the specification requires to exist")
    public List<Map<String, Object>> presetCatalogue() {
        return PresetProcesses.all().stream().map(preset -> Map.<String, Object>of("code", preset.code(),
                "name", preset.name())).toList();
    }

    /** Installs the presets, once. */
    @PostMapping("/workflows/presets/install")
    @PreAuthorize(DEFINE)
    @Operation(summary = "Installs the presets; a second run publishes nothing twice")
    public Map<String, Object> installPresets() {
        return presets.install(tenant(), CurrentUser.required());
    }

    // ---------------------------------------------------------------- runs

    /**
     * Starts a run.
     *
     * <p>The key is passed through rather than defaulted: a caller that retried after a timeout must not
     * start a second instance of the same process, and only the caller knows what its retry is a retry of.</p>
     */
    @PostMapping("/workflows/{definitionId}/runs")
    @PreAuthorize(RUN)
    @Operation(summary = "Starts a run; the same key returns the run that exists")
    public ResponseEntity<Map<String, Object>> start(@PathVariable UUID definitionId,
                                                     @RequestBody(required = false) StartBody body) {
        UUID tenantId = tenant();
        WorkflowEngine.Run run = engine.start(tenantId, definitionId,
                body == null ? null : body.triggerType(), body == null ? null : body.triggerPayload(),
                body == null ? null : body.subjectType(), body == null ? null : body.subjectId(),
                body == null ? null : body.caseId(), body == null ? null : body.idempotencyKey(),
                CurrentUser.required());
        return ResponseEntity.status(run.created() ? HttpStatus.CREATED : HttpStatus.OK).body(run.asMap());
    }

    /**
     * Starts a run and executes it until it stops.
     *
     * <p>This is the difference between "a run exists" and "the process did something". The chain is
     * executed until it ends, waits, or pauses for a person — the caller does not have to know how many
     * steps a constellation has.</p>
     */
    @PostMapping("/workflows/{definitionId}/run")
    @PreAuthorize(RUN)
    @Operation(summary = "Starts a run and executes it until it ends, waits or pauses")
    public ResponseEntity<Map<String, Object>> run(@PathVariable UUID definitionId,
                                                   @RequestBody(required = false) StartBody body) {
        UUID tenantId = tenant();
        WorkflowRunService.Outcome outcome = runs.run(tenantId, definitionId,
                body == null ? null : body.triggerType(), body == null ? null : body.triggerPayload(),
                body == null ? null : body.subjectType(), body == null ? null : body.subjectId(),
                body == null ? null : body.caseId(), body == null ? null : body.idempotencyKey(),
                CurrentUser.required());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.asMap());
    }

    /**
     * Answers the pause of a run.
     *
     * <p>{@code APPROVE} and {@code SKIP} continue past the failed step; {@code RETRY} executes the same step
     * again with the supplied inputs. The response is the state the run reached afterwards, which saves the
     * caller a second request.</p>
     */
    @PostMapping("/workflow-runs/{runId}/resume")
    @PreAuthorize(RUN)
    @Operation(summary = "APPROVE, RETRY or SKIP a run paused for human review")
    public Map<String, Object> resume(@PathVariable UUID runId,
                                      @RequestBody(required = false) ResumeBody body) {
        WorkflowRunService.Outcome outcome = runs.resume(tenant(), runId,
                body == null ? null : body.decision(), body == null ? null : body.inputs(),
                CurrentUser.required());
        Map<String, Object> result = new java.util.LinkedHashMap<>(outcome.asMap());
        // The answer says which decision was applied, so a client that retried does not have to remember it.
        result.put("decision", body == null || body.decision() == null ? null
                : body.decision().trim().toUpperCase(java.util.Locale.ROOT));
        return result;
    }

    /** The runs of an account in a given status. */
    @GetMapping("/workflow-runs")
    @PreAuthorize(SEE)
    @Operation(summary = "The runs in a given status")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "RUNNING") String status,
                                          @RequestParam(defaultValue = "50") int limit) {
        return engine.runsInStatus(tenant(), status, limit);
    }

    /**
     * Runs the step a run is on.
     *
     * <p>Safe to call again: the step is claimed so that it runs once, and a second call does nothing rather
     * than doing it twice — which is what makes it safe after a crash without knowing whether the previous
     * call got anywhere.</p>
     */
    @PostMapping("/workflow-runs/{runId}/advance")
    @PreAuthorize(RUN)
    @Operation(summary = "Runs the current step; a second call does not repeat it")
    public Map<String, Object> advance(@PathVariable UUID runId) {
        return engine.advance(tenant(), runId).asMap();
    }

    /**
     * Delivers an event to a run.
     *
     * <p>Reported with whether it did anything, rather than as a failure: queues repeat and webhooks retry,
     * and the second delivery is expected rather than an error.</p>
     */
    @PostMapping("/workflow-runs/{runId}/events")
    @PreAuthorize(RUN)
    @Operation(summary = "Delivers an event; a second delivery changes nothing")
    public Map<String, Object> deliverEvent(@PathVariable UUID runId, @RequestBody EventBody body) {
        boolean delivered = engine.deliverEvent(tenant(), runId, body.eventName(), body.payload());
        return Map.of("delivered", delivered,
                "note", delivered ? "Das Ereignis hat den Lauf fortgesetzt."
                        : "Der Lauf wartet nicht auf dieses Ereignis; es wurde nichts geändert.");
    }

    /** Continues the runs whose waiting time has passed. */
    @PostMapping("/workflow-runs/resume-due")
    @PreAuthorize(RUN)
    @Operation(summary = "Continues the runs whose waiting time has passed")
    public Map<String, Object> resumeDue(@RequestParam(defaultValue = "50") int limit) {
        List<UUID> resumed = engine.resumeDue(tenant(), limit);
        return Map.of("resumed", resumed.size(), "ids", resumed.stream().map(UUID::toString).toList());
    }

    /** Approves or refuses a run that is waiting for a person. */
    @PostMapping("/workflow-runs/{runId}/approval")
    @PreAuthorize(RUN)
    @Operation(summary = "Approves or refuses a run; the person comes from the session")
    public Map<String, Object> approve(@PathVariable UUID runId, @RequestBody ApprovalBody body) {
        // From the session, never from the body: an approval that named itself would not be one.
        UUID actor = CurrentUser.required();
        return engine.approve(tenant(), runId, actor, Boolean.TRUE.equals(body.accepted()), body.note())
                .asMap();
    }

    /** Cancels a run. */
    @PostMapping("/workflow-runs/{runId}/cancel")
    @PreAuthorize("hasAnyAuthority('workflows.cancel','workflows.edit')")
    @Operation(summary = "Cancels a run")
    public ResponseEntity<Void> cancelRun(@PathVariable UUID runId,
                                          @RequestBody(required = false) ApprovalBody body) {
        engine.cancel(tenant(), runId, body == null ? null : body.note());
        return ResponseEntity.noContent().build();
    }

    /** What happened in a run: its steps and its events. */
    @GetMapping("/workflow-runs/{runId}")
    @PreAuthorize(SEE)
    @Operation(summary = "A run with its steps and events")
    public Map<String, Object> describeRun(@PathVariable UUID runId) {
        return engine.describe(tenant(), runId);
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    /** A small helper so that a caller sees which statuses a run can be listed in. */
    @GetMapping("/workflow-runs/statuses")
    @PreAuthorize(SEE)
    @Operation(summary = "The statuses a run can be listed in")
    public List<String> runStatuses() {
        List<String> statuses = new java.util.ArrayList<>(List.of("PENDING", "RUNNING", "WAITING",
                "WAITING_APPROVAL", "PAUSED_FOR_HUMAN_REVIEW", "COMPLETED", "FAILED", "CANCELLED",
                "COMPENSATED"));
        return statuses;
    }

}
