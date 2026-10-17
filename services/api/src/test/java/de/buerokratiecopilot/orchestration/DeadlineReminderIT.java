package de.buerokratiecopilot.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Deadlines derived from documents reach a person (spec §12, §13).
 *
 * <p><strong>What was missing.</strong> {@code OrchestrationWatchdog.remind()} follows {@code tasks.remind_at},
 * which the orchestrator sets to one day before the internal due date and only when that date is still in the
 * future. A deadline that arrives late, or one a person enters, therefore never produced a notification: the
 * audit found 1310 document deadlines without a reminder date and not one {@code task.reminder} in the
 * database. The deadline was in the calendar and nobody was told.</p>
 *
 * <p>The test walks the case that failed: a task with a deadline two days away, assigned to somebody, and the
 * watchdog run twice — once it must remind, the second time it must not, because a system that repeats itself
 * is one people stop reading.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeadlineReminderIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrchestrationWatchdog watchdog;

    private record Tenant(UUID tenantId, UUID userId) {
    }

    private Tenant register() throws Exception {
        String email = "rem-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Frist Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));

        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        return new Tenant(tenantId, userId);
    }

    /** A task with a deadline {@code inDays} away, assigned to the person. */
    private UUID taskWithDeadline(Tenant owner, int inDays) {
        return RequestContext.runAs(owner.tenantId(), () -> {
            UUID taskId = jdbc.queryForObject("""
                    insert into tasks (id, tenant_id, title, status, priority, priority_source,
                                       created_by_user_id, source)
                    values (gen_random_uuid(), ?, ?, 'NEW', 'NORMAL', 'MANUAL', ?, 'MANUAL')
                    returning id
                    """, UUID.class, owner.tenantId(), "Rechnung prüfen", owner.userId());
            jdbc.update("""
                    insert into task_assignees (id, task_id, tenant_id, user_id, role, assigned_at)
                    values (gen_random_uuid(), ?, ?, ?, 'ASSIGNEE', now())
                    """, taskId, owner.tenantId(), owner.userId());
            jdbc.update("""
                    insert into task_deadlines (id, task_id, tenant_id, deadline_type, due_date,
                                                source, is_active, requires_verification)
                    values (gen_random_uuid(), ?, ?, 'DOCUMENT_DATE', current_date + make_interval(days => ?),
                            'DOCUMENT', true, false)
                    """, taskId, owner.tenantId(), inDays);
            return taskId;
        });
    }

    private int remindersFor(UUID taskId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from notifications
                 where entity_id = ? and notification_type = 'task.deadline_approaching'
                """, Integer.class, taskId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("a deadline inside the window reminds the responsible person once")
    void aDeadlineInsideTheWindowRemindsOnce() throws Exception {
        Tenant owner = register();
        UUID taskId = taskWithDeadline(owner, 2);
        assertThat(remindersFor(taskId)).as("nothing before the watchdog runs").isZero();

        // Two days away, window of seven: inside.
        int first = watchdog.remindDeadlines(7, owner.tenantId());
        assertThat(first).as("the deadline is inside the window").isEqualTo(1);
        assertThat(remindersFor(taskId)).isEqualTo(1);

        // The watchdog runs every minute. A second reminder for the same deadline is how a notification
        // becomes noise.
        int second = watchdog.remindDeadlines(7, owner.tenantId());
        assertThat(second).as("the same deadline is not reminded twice").isZero();
        assertThat(remindersFor(taskId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a deadline beyond the window is left alone until it comes closer")
    void aDeadlineBeyondTheWindowWaits() throws Exception {
        Tenant owner = register();
        UUID taskId = taskWithDeadline(owner, 20);

        // Twenty days away with a seven day window: not yet.
        assertThat(watchdog.remindDeadlines(7, owner.tenantId())).isZero();
        assertThat(remindersFor(taskId)).isZero();

        // Closer, and it is reminded.
        assertThat(watchdog.remindDeadlines(30, owner.tenantId())).isEqualTo(1);
        assertThat(remindersFor(taskId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a deadline already in the past is not reminded as if it were coming")
    void aPastDeadlineIsNotReminded() throws Exception {
        Tenant owner = register();
        UUID taskId = taskWithDeadline(owner, -2);

        // Two days ago: the overdue path owns that, not the approaching one.
        assertThat(watchdog.remindDeadlines(7, owner.tenantId())).isZero();
        assertThat(remindersFor(taskId)).isZero();
    }

    @Test
    @DisplayName("another account's deadline is not reminded by this run")
    void anotherAccountIsNotReminded() throws Exception {
        Tenant first = register();
        Tenant second = register();
        UUID foreignTask = taskWithDeadline(first, 2);

        watchdog.remindDeadlines(7, second.tenantId());

        assertThat(remindersFor(foreignTask))
                .as("a run for one account must not touch another account's deadlines")
                .isZero();
    }
}
