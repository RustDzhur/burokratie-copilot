package de.buerokratiecopilot.autopilot.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.autopilot.ReplyLinkService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.util.JsonColumns;

/** Which case an incoming letter answers (task CA-06): the suggestions, and the decision of a person. */
@RestController
@RequestMapping("/api/v1")
public class ReplyLinkController {

    private final ReplyLinkService replies;
    private final JdbcTemplate jdbc;

    public ReplyLinkController(ReplyLinkService replies, JdbcTemplate jdbc) {
        this.replies = replies;
        this.jdbc = jdbc;
    }

    private static boolean seesAll() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "cases.assign".equals(a.getAuthority()));
    }

    @GetMapping("/mail/messages/{messageId}/case-candidates")
    @PreAuthorize("hasAuthority('mail.view')")
    public Map<String, Object> candidates(@PathVariable UUID messageId) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        var rows = JsonColumns.parse(jdbc.queryForList(
                "select case_link_status, case_id, reply_candidates from mail_messages where id = ? and tenant_id = ? and direction = 'INBOUND'",
                messageId, tenantId));
        if (rows.isEmpty()) {
            throw ApiException.notFound("message");
        }
        return rows.get(0);
    }

    @PostMapping("/cases/{caseId}/replies/{messageId}/confirm")
    @PreAuthorize("hasAuthority('cases.edit')")
    public Map<String, Object> confirm(@PathVariable UUID caseId, @PathVariable UUID messageId) {
        ReplyLinkService.Result result = replies.confirm(RequestContext.tenantId().orElseThrow(), messageId, caseId, CurrentUser.required(), seesAll());
        return Map.of("status", result.status(), "caseId", caseId);
    }
}
