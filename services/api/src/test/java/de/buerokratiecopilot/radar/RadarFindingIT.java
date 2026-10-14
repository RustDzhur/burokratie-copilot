package de.buerokratiecopilot.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import de.buerokratiecopilot.radar.RadarFindingService.Draft;
import de.buerokratiecopilot.radar.RadarFindingService.Outcome;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * RD-01 and RD-02: the schema isolates tenants and keeps a history nobody edits; the same situation is one finding however
 * often and however concurrently it is found; a decision of a person survives re-evaluation until new data arrives.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RadarFindingIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RadarFindingService findings;

    private UUID newTenant() throws Exception {
        String email = "radar-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Radar Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
    }

    private static Draft draft(String key, String hash) {
        return new Draft(key, "INVOICE_ANOMALY", "B1", 1, "Betrag weicht ab", "Der Betrag liegt über dem letzten Betrag",
                RadarSeverity.MEDIUM, 0.9, null, null, null, List.of("AMOUNT_CHANGED"), List.of(), List.of(), hash);
    }

    private int count(UUID tenant, String sql, Object... args) {
        Integer n = RequestContext.runAs(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("the same situation found twice is one finding with one history entry of detection")
    void foundTwiceIsOne() throws Exception {
        UUID tenant = newTenant();
        var first = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("B1:x", "h1")));
        var second = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("B1:x", "h1")));

        assertThat(first.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(second.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count(tenant, "select count(*) from radar_findings where tenant_id = ?", tenant)).isEqualTo(1);
        assertThat(count(tenant, "select count(*) from radar_finding_events where finding_id = ? and event_type = 'DETECTED'",
                first.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("eight evaluations at the same time create one finding")
    void concurrentEvaluation() throws Exception {
        UUID tenant = newTenant();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<RadarFindingService.Result>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("B2:parallel", "h"))));
            }
            List<UUID> ids = new ArrayList<>();
            int created = 0;
            for (Future<RadarFindingService.Result> future : pool.invokeAll(calls)) {
                var result = future.get();
                ids.add(result.id());
                if (result.outcome() == Outcome.CREATED) {
                    created++;
                }
            }
            assertThat(ids.stream().distinct().count()).isEqualTo(1);
            assertThat(created).as("exactly one evaluation created it").isEqualTo(1);
            assertThat(count(tenant, "select count(*) from radar_findings where tenant_id = ?", tenant)).isEqualTo(1);
            assertThat(count(tenant, "select count(*) from radar_finding_events where finding_id = ?", ids.get(0))).isEqualTo(1);
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a dismissed finding stays dismissed when found again, and is reopened only by new significant data")
    void dismissedStaysUntilNewData() throws Exception {
        UUID tenant = newTenant();
        var created = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("B1:y", "h1")));
        RequestContext.runAs(tenant, () -> findings.transition(tenant, created.id(), RadarFindingStatus.DISMISSED, null,
                "Absichtliche Erhöhung", null));

        var again = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("B1:y", "h1")));
        assertThat(again.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(again.status()).isEqualTo(RadarFindingStatus.DISMISSED);

        var changed = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("B1:y", "h2")));
        assertThat(changed.outcome()).isEqualTo(Outcome.REOPENED);
        assertThat(changed.status()).isEqualTo(RadarFindingStatus.REOPENED);
        assertThat(count(tenant, "select reopen_count from radar_findings where id = ?", created.id())).isEqualTo(1);
        assertThat(count(tenant, "select count(*) from radar_finding_events where finding_id = ? and event_type = 'REOPENED'",
                created.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("the platform's evaluation does not undo a decision of a person on an open finding either")
    void openFindingKeepsItsStatus() throws Exception {
        UUID tenant = newTenant();
        var created = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("B1:z", "h1")));
        RequestContext.runAs(tenant, () -> findings.transition(tenant, created.id(), RadarFindingStatus.CONFIRMED, null, null, null));

        var changed = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("B1:z", "h2")));
        assertThat(changed.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(changed.status()).isEqualTo(RadarFindingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("an impossible move is refused, closing needs a reason, snoozing needs a future time, and the move is recorded")
    void transitions() throws Exception {
        UUID tenant = newTenant();
        var created = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("D1:t", "h")));

        assertThatThrownBy(() -> RequestContext.runAs(tenant,
                () -> findings.transition(tenant, created.id(), RadarFindingStatus.REOPENED, null, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestContext.runAs(tenant,
                () -> findings.transition(tenant, created.id(), RadarFindingStatus.DISMISSED, null, " ", null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestContext.runAs(tenant,
                () -> findings.transition(tenant, created.id(), RadarFindingStatus.SNOOZED, null, null,
                        Instant.now().minus(1, ChronoUnit.HOURS)))).isInstanceOf(ApiException.class);

        RequestContext.runAs(tenant, () -> findings.transition(tenant, created.id(), RadarFindingStatus.SNOOZED, null, null,
                Instant.now().plus(2, ChronoUnit.DAYS)));
        assertThat(count(tenant, "select count(*) from radar_finding_events where finding_id = ? and event_type = 'STATUS_CHANGED' and from_status = 'NEW' and to_status = 'SNOOZED'",
                created.id())).isEqualTo(1);
        assertThat(count(tenant, "select count(*) from audit_events where tenant_id = ? and action = 'RADAR_FINDING_SNOOZED'", tenant))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a snooze whose time is up comes back as reopened")
    void expiredSnooze() throws Exception {
        UUID tenant = newTenant();
        var created = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("D1:s", "h")));
        RequestContext.runAs(tenant, () -> findings.transition(tenant, created.id(), RadarFindingStatus.SNOOZED, null, null,
                Instant.now().plus(1, ChronoUnit.HOURS)));
        RequestContext.runAs(tenant, () -> jdbc.update("update radar_findings set snoozed_until = now() - interval '1 minute' where id = ?",
                created.id()));

        int reopened = RequestContext.runAs(tenant, () -> findings.reopenExpiredSnoozes(tenant));
        assertThat(reopened).isEqualTo(1);
        assertThat(RequestContext.runAs(tenant, () -> jdbc.queryForObject("select status from radar_findings where id = ?",
                String.class, created.id()))).isEqualTo("REOPENED");
    }

    @Test
    @DisplayName("the announcement is claimed once per finding")
    void notifiedOnce() throws Exception {
        UUID tenant = newTenant();
        var created = RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("A1:n", "h")));
        assertThat(RequestContext.runAs(tenant, () -> findings.claimNotification(tenant, created.id()))).isTrue();
        assertThat(RequestContext.runAs(tenant, () -> findings.claimNotification(tenant, created.id()))).isFalse();
        RequestContext.runAs(tenant, () -> findings.raise(tenant, draft("A1:n", "h2")));
        assertThat(RequestContext.runAs(tenant, () -> findings.claimNotification(tenant, created.id()))).isFalse();
    }

    @Test
    @DisplayName("another tenant sees no finding, cannot decide on it, and a history entry cannot be edited")
    void isolationAndHistory() throws Exception {
        UUID owner = newTenant();
        UUID stranger = newTenant();
        var created = RequestContext.runAs(owner, () -> findings.raise(owner, draft("B3:i", "h")));

        assertThat(count(stranger, "select count(*) from radar_findings")).isZero();
        assertThat(count(stranger, "select count(*) from radar_finding_events")).isZero();
        assertThatThrownBy(() -> RequestContext.runAs(stranger,
                () -> findings.transition(stranger, created.id(), RadarFindingStatus.CONFIRMED, null, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestContext.runAs(owner,
                () -> jdbc.update("update radar_finding_events set reason = 'changed' where finding_id = ?", created.id())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
