package de.buerokratiecopilot.ai.assistant.web;

import java.util.LinkedHashMap;
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

import de.buerokratiecopilot.ai.assistant.AssistantService;
import de.buerokratiecopilot.ai.security.UntrustedContent;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The assistant's tools, its proposals and the confirmation of them (spec §10.4, §32).
 *
 * <p><strong>Why this controller exists.</strong> {@code AssistantService} — the tools, the policy check, the
 * proposal flow, the confirmation — had two tests and no HTTP surface at all. The other controller under
 * {@code /assistant} speaks to the retrieval service instead, so every tool the assistant owns was reachable
 * only from a test: a customer could not ask it to create a task, and a confirmation could not be given
 * because no proposal could be made.</p>
 *
 * <p><strong>Nothing here can act on its own.</strong> A tool with an effect comes back as a {@code PROPOSAL}
 * and does nothing until the same person confirms it; the parameters are proposed, so the confirmation runs
 * the tool. That is the rule the policy already enforced and which this route finally exposes.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant tools", description = "Tools, proposals and their confirmation")
public class AssistantToolController {

    /** The model and the instruction version a tool call is recorded against. */
    private static final String MODEL = "assistant.tool.v1";
    private static final String INSTRUCTION_VERSION = "assistant.tools.v1";

    private final AssistantService assistant;

    public AssistantToolController(AssistantService assistant) {
        this.assistant = assistant;
    }

    /** What the caller asks the assistant to do. */
    public record ToolRequest(String tool, Map<String, Object> parameters, String question) {
    }

    /**
     * Asks the assistant to use a tool.
     *
     * <p>A tool with an effect returns {@code 202} and a proposal id rather than acting: the answer says the
     * action waits for a confirmation, and the confirmation is a second call by the same person.</p>
     */
    @PostMapping("/tools")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Asks the assistant to use a tool; a tool with an effect comes back as a proposal")
    public ResponseEntity<Map<String, Object>> useTool(@RequestBody ToolRequest request) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID userId = de.buerokratiecopilot.shared.context.CurrentUser.required();
        if (request == null || request.tool() == null || request.tool().isBlank()) {
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "Es wurde kein Werkzeug angegeben");
        }
        AssistantService.Turn turn = new AssistantService.Turn(tenantId, userId,
                request.question() == null ? "" : request.question(), MODEL, INSTRUCTION_VERSION,
                null, UntrustedContent.Origin.PLATFORM, null);

        AssistantService.Answer answer = assistant.ask(turn, request.tool(),
                request.parameters() == null ? Map.of() : request.parameters());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", answer.kind());
        body.put("message", answer.message());
        body.put("proposalId", answer.proposalId() == null ? null : answer.proposalId().toString());
        body.put("tool", answer.toolName());
        body.put("preview", answer.preview());
        body.put("result", answer.result());
        body.put("code", answer.refusalCode());
        // A proposal is not done: the caller has to confirm it, and saying so here is what stops the screen
        // from showing a success for work that has not happened.
        return ResponseEntity
                .status("PROPOSAL".equals(answer.kind()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(body);
    }

    /** Confirms a proposal; this is where the tool actually runs. */
    @PostMapping("/proposals/{id}/confirm")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Confirms a proposal and runs the tool")
    public Map<String, Object> confirm(@PathVariable UUID id) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID userId = de.buerokratiecopilot.shared.context.CurrentUser.required();
        AssistantService.Answer answer = assistant.confirm(tenantId, userId, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", answer.message());
        body.put("result", answer.result());
        return body;
    }

    /** Rejects a proposal without applying it. */
    @PostMapping("/proposals/{id}/reject")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Rejects a proposal without applying it")
    public ResponseEntity<Void> reject(@PathVariable UUID id) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID userId = de.buerokratiecopilot.shared.context.CurrentUser.required();
        assistant.reject(tenantId, userId, id);
        return ResponseEntity.noContent().build();
    }

    /** The proposals waiting for this person. */
    @GetMapping("/proposals")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The proposals waiting for the caller's confirmation")
    public Map<String, Object> proposals() {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID userId = de.buerokratiecopilot.shared.context.CurrentUser.required();
        List<Map<String, Object>> open = assistant.openProposals(tenantId, userId);
        return Map.of("proposals", open, "count", open.size());
    }

    /** What the assistant did for this person, newest first. */
    @GetMapping("/history")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The assistant's history for the caller")
    public Map<String, Object> history(@RequestParam(required = false) Integer limit) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID userId = de.buerokratiecopilot.shared.context.CurrentUser.required();
        int effective = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        return Map.of("history", assistant.history(tenantId, userId, effective));
    }
}
