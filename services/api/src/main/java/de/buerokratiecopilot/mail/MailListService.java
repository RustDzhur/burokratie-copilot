package de.buerokratiecopilot.mail;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mail list of the mail screen: what came in, what went out and what still waits for a decision.
 *
 * <p>Three sources, one list. Received and sent mail are rows of {@code mail_messages}; a letter still
 * being written or waiting for approval is a row of {@code outbound_messages} and is shown as a draft. The
 * screen's "needs reply" is not stored anywhere: it is an inbound message that no outbound message answers.</p>
 */
@Service
public class MailListService {

    public enum Folder { INBOX, SENT, DRAFTS }

    public enum Status { NEW, PROCESSED, NEEDS_REPLY }

    private final JdbcTemplate jdbc;

    public MailListService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> list(UUID tenantId, String query, List<Folder> folders,
                                          List<Status> statuses, Pageable pageable) {
        String term = query == null ? "" : query.strip();
        List<Folder> wanted = folders == null || folders.isEmpty() ? List.of(Folder.values()) : folders;
        List<Status> wantedStatuses = statuses == null || statuses.isEmpty() ? List.of(Status.values()) : statuses;

        String union = """
                select m.id::text as id, coalesce(m.subject, '') as subject,
                       coalesce(m.from_name, m.from_address::text, '') as sender,
                       case m.direction when 'INBOUND' then 'INBOX' else 'SENT' end as folder,
                       case
                         when m.processing_status in ('PROCESSED', 'IGNORED') then 'PROCESSED'
                         when m.direction = 'INBOUND' and not exists (
                                select 1 from mail_messages r where r.tenant_id = m.tenant_id
                                   and r.in_reply_to = m.external_id and m.external_id is not null)
                              then 'NEEDS_REPLY'
                         else 'NEW' end as status,
                       coalesce(m.received_at, m.sent_at, m.created_at) as moment,
                       m.attachment_count as attachment_count
                from mail_messages m
                where m.tenant_id = ? and m.deleted_at is null
                union all
                select o.id::text, coalesce(o.subject, ''), coalesce(o.from_address::text, ''), 'DRAFTS',
                       'NEW', o.created_at, 0
                from outbound_messages o
                where o.tenant_id = ? and o.deleted_at is null and o.status in ('DRAFT', 'PENDING_APPROVAL')
                """;
        String filter = " where folder in (?, ?, ?) and status in (?, ?, ?) and (? = '' or lower(subject) like '%' || lower(?) || '%'"
                + " or lower(sender) like '%' || lower(?) || '%')";
        Object[] arguments = {tenantId, tenantId,
                wanted.contains(Folder.INBOX) ? "INBOX" : "-", wanted.contains(Folder.SENT) ? "SENT" : "-",
                wanted.contains(Folder.DRAFTS) ? "DRAFTS" : "-",
                wantedStatuses.contains(Status.NEW) ? "NEW" : "-",
                wantedStatuses.contains(Status.PROCESSED) ? "PROCESSED" : "-",
                wantedStatuses.contains(Status.NEEDS_REPLY) ? "NEEDS_REPLY" : "-", term, term, term};

        Long total = jdbc.queryForObject("select count(*) from (" + union + ") u" + filter, Long.class,
                arguments);
        Object[] paged = java.util.Arrays.copyOf(arguments, arguments.length + 2);
        paged[arguments.length] = pageable.getPageSize();
        paged[arguments.length + 1] = pageable.getOffset();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from (" + union + ") u" + filter + " order by moment desc limit ? offset ?", paged);
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /**
     * One message for reading. The content is returned as plain text only: a mail from outside is untrusted, so
     * its HTML is never handed to the browser, only the readable text of it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.id::text as id, coalesce(m.subject, '') as subject, m.from_name, m.from_address::text as from_address,
                       m.to_addresses::text as to_addresses, m.direction, m.attachment_count,
                       coalesce(m.received_at, m.sent_at, m.created_at) as moment, m.body_text, m.body_html,
                       m.is_suspicious
                from mail_messages m where m.id = ? and m.tenant_id = ? and m.deleted_at is null
                """, id, tenantId);
        if (rows.isEmpty()) {
            // A draft is not a received message, but it opens from the same list.
            rows = jdbc.queryForList("""
                    select o.id::text as id, coalesce(o.subject, '') as subject, null as from_name,
                           o.from_address::text as from_address, o.to_addresses::text as to_addresses,
                           'DRAFT' as direction, 0 as attachment_count, o.created_at as moment,
                           o.body_text, o.body_html, false as is_suspicious
                    from outbound_messages o where o.id = ? and o.tenant_id = ? and o.deleted_at is null
                    """, id, tenantId);
        }
        if (rows.isEmpty()) {
            throw de.buerokratiecopilot.shared.error.ApiException.notFound("Mail");
        }
        Map<String, Object> row = new java.util.LinkedHashMap<>(rows.get(0));
        Object text = row.remove("body_text");
        Object html = row.remove("body_html");
        String body = text != null && !String.valueOf(text).isBlank() ? String.valueOf(text) : htmlToText(html);
        row.put("body", body.length() > 200_000 ? body.substring(0, 200_000) : body);
        return row;
    }

    static String htmlToText(Object html) {
        if (html == null) {
            return "";
        }
        return String.valueOf(html).replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>", "\n").replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").strip();
    }
}
