package de.buerokratiecopilot.agents.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.agents.core.AgentManifest;
import de.buerokratiecopilot.agents.core.AgentRegistry;
import de.buerokratiecopilot.agents.core.AgentRunner;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Agent catalogue and run API (runtime contract, docs/agents/RUNTIME.md).
 *
 * <p>The catalogue is the touch surface: a person can see what each agent promises, what
 * triggers it, and run it by hand against their own data — the result is journaled and
 * audited instead of being swallowed.</p>
 */
@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "Agents", description = "Agent catalogue, manual runs and run journal")
public class AgentsController {

    private final AgentRegistry registry;
    private final AgentRunner runner;

    public AgentsController(AgentRegistry registry, AgentRunner runner) {
        this.registry = registry;
        this.runner = runner;
    }

    public record CatalogEntry(String id, String name, String category, String description,
                               List<String> functions, List<String> triggers, boolean implemented,
                               long runCount) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agents.view')")
    @Operation(summary = "Agent catalogue")
    public List<CatalogEntry> catalog() {
        return registry.all().stream()
                .map(this::entry)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('agents.view')")
    @Operation(summary = "One agent: manifest plus recent runs")
    public ResponseEntity<?> one(@PathVariable String id,
                                 @RequestParam(defaultValue = "20") int limit) {
        Optional<AgentManifest> manifest = registry.manifest(id);
        if (manifest.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UUID tenantId = RequestContext.tenantId().orElse(null);
        List<AgentRunner.RunRow> runs = tenantId == null ? List.of()
                : runner.recentRuns(tenantId, id, limit);
        return ResponseEntity.ok(Map.of(
                "manifest", manifest.get(),
                "implemented", registry.isImplemented(id),
                "runCount", runner.runCount(id),
                "runs", runs));
    }

    public record RunRequest(String trigger, Map<String, String> inputs) {
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasAuthority('agents.run')")
    @Operation(summary = "Run one agent manually against the current workspace")
    public AgentRunner.RunResponse run(@PathVariable String id, @RequestBody(required = false) RunRequest request) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID actorUserId = CurrentUser.required();
        String trigger = request == null ? "manual" : request.trigger();
        Map<String, String> inputs = request == null || request.inputs() == null
                ? Map.of() : request.inputs();
        return runner.run(tenantId, actorUserId, id, trigger, inputs);
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize("hasAuthority('agents.view')")
    @Operation(summary = "Run journal of one agent")
    public List<AgentRunner.RunRow> runs(@PathVariable String id,
                                         @RequestParam(defaultValue = "20") int limit) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        return runner.recentRuns(tenantId, id, limit);
    }

    private CatalogEntry entry(AgentManifest manifest) {
        return new CatalogEntry(manifest.id(), manifest.name(), manifest.category(),
                manifest.description(), manifest.functions(), manifest.triggers(),
                registry.isImplemented(manifest.id()), runner.runCount(manifest.id()));
    }
}
