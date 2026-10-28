package de.buerokratiecopilot.billing;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.mail.EmailMessage;
import de.buerokratiecopilot.shared.mail.EmailSender;

/**
 * Walks the subscriptions once an hour:
 * <ol>
 *   <li>access that has run out becomes EXPIRED, the retention period starts, the owner is told;</li>
 *   <li>owners are reminded 14, 7 and 1 day before the access ends;</li>
 *   <li>after expiry owners are reminded 30, 7 and 1 day before their data is deleted;</li>
 *   <li>when the retention period has run out, the tenant is deleted.</li>
 * </ol>
 * Every step first <em>claims</em> its rows with one atomic update, so several instances never mail or delete the
 * same tenant twice.
 */
@Component
public class SubscriptionLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleJob.class);

    private final JdbcTemplate jdbc;
    private final PlatformSettings settings;
    private final TenantPurgeService purge;
    private final EmailSender emailSender;
    private final String baseUrl;
    private final boolean enabled;

    public SubscriptionLifecycleJob(JdbcTemplate jdbc, PlatformSettings settings, TenantPurgeService purge,
                                    EmailSender emailSender,
                                    @Value("${burokratie.security.public-base-url:http://localhost:3000}") String baseUrl,
                                    @Value("${burokratie.billing.lifecycle-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.purge = purge;
        this.emailSender = emailSender;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${burokratie.billing.lifecycle-poll-ms:3600000}",
            initialDelayString = "${burokratie.billing.lifecycle-initial-delay-ms:120000}")
    public void run() {
        if (enabled) {
            runOnce();
        }
    }

    /** One pass; public so tests and the administrator can run it on demand. */
    public void runOnce() {
        try {
            RequestContext.runAs((UUID) null, () -> {
                expire();
                remindBeforeExpiry();
                remindBeforeDeletion();
                purgeDue();
                return null;
            });
        }
        catch (RuntimeException ex) {
            log.error("Subscription lifecycle pass failed", ex);
        }
    }

    private void expire() {
        List<UUID> expired = jdbc.query("""
                update subscriptions set status = 'EXPIRED',
                    retention_until = access_until + make_interval(days => ?)
                where status in ('TRIAL', 'ACTIVE') and access_until < now()
                returning tenant_id
                """, (rs, i) -> rs.getObject(1, UUID.class), settings.retentionDays());
        for (UUID tenantId : expired) {
            mailOwners(tenantId, "Ihr Zugang zum Bürokratie-Copilot ist abgelaufen", """
                    Guten Tag,

                    Ihr Zugang zum Bürokratie-Copilot ist abgelaufen. Ihre Daten sind weiterhin gespeichert, Sie können aber
                    nicht darauf zugreifen.

                    Ihre Daten bleiben %d Tage erhalten und werden danach automatisch und unwiderruflich gelöscht.

                    Sie entscheiden, was damit geschieht:
                      * Tarif verlängern und weiterarbeiten,
                      * Ihre Daten herunterladen,
                      * Ihre Daten sofort löschen lassen.

                    Alles finden Sie nach der Anmeldung hier:
                    %s/access-ended
                    """.formatted(settings.retentionDays(), baseUrl));
        }
    }

    private void remindBeforeExpiry() {
        for (int days : new int[] {1, 7, 14}) {
            List<UUID> due = jdbc.query("""
                    update subscriptions set reminders_sent = reminders_sent || '{"D1":true,"D7":true,"D14":true}'::jsonb
                    where status in ('TRIAL', 'ACTIVE') and access_until >= now()
                      and access_until < now() + make_interval(days => ?)
                      and not jsonb_exists(reminders_sent, ?)
                    returning tenant_id
                    """.replace("{\"D1\":true,\"D7\":true,\"D14\":true}", reminderJson(days)),
                    (rs, i) -> rs.getObject(1, UUID.class), days, "D" + days);
            for (UUID tenantId : due) {
                mailOwners(tenantId, "Ihr Zugang zum Bürokratie-Copilot endet in %d %s".formatted(days,
                        days == 1 ? "Tag" : "Tagen"), """
                        Guten Tag,

                        Ihr Zugang zum Bürokratie-Copilot endet in höchstens %d %s. Danach können Sie sich nicht mehr
                        anmelden, bis der Tarif verlängert wurde.

                        Tarif verlängern: %s/billing
                        """.formatted(days, days == 1 ? "Tag" : "Tagen", baseUrl));
            }
        }
    }

    private void remindBeforeDeletion() {
        for (int days : new int[] {1, 7, 30}) {
            List<UUID> due = jdbc.query("""
                    update subscriptions set reminders_sent = reminders_sent || ?::jsonb
                    where status = 'EXPIRED' and retention_until >= now()
                      and retention_until < now() + make_interval(days => ?)
                      and not jsonb_exists(reminders_sent, ?)
                    returning tenant_id
                    """, (rs, i) -> rs.getObject(1, UUID.class), deletionJson(days), days, "R" + days);
            for (UUID tenantId : due) {
                mailOwners(tenantId, "Ihre Daten werden in höchstens %d %s gelöscht".formatted(days,
                        days == 1 ? "Tag" : "Tagen"), """
                        Guten Tag,

                        Ihr Zugang zum Bürokratie-Copilot ist abgelaufen. Ihre Daten werden in höchstens %d %s
                        automatisch und unwiderruflich gelöscht.

                        Verlängern, Daten herunterladen oder sofort löschen: %s/access-ended
                        """.formatted(days, days == 1 ? "Tag" : "Tagen", baseUrl));
            }
        }
    }

    private void purgeDue() {
        List<UUID> due = jdbc.queryForList("""
                select tenant_id from subscriptions where status = 'EXPIRED' and retention_until < now()
                """, UUID.class);
        for (UUID tenantId : due) {
            try {
                purge.purge(tenantId);
            }
            catch (RuntimeException ex) {
                log.error("Tenant {} could not be purged", tenantId, ex);
            }
        }
    }

    private static String reminderJson(int days) {
        return switch (days) {
            case 1 -> "{\"D1\":true,\"D7\":true,\"D14\":true}";
            case 7 -> "{\"D7\":true,\"D14\":true}";
            default -> "{\"D14\":true}";
        };
    }

    private static String deletionJson(int days) {
        return switch (days) {
            case 1 -> "{\"R1\":true,\"R7\":true,\"R30\":true}";
            case 7 -> "{\"R7\":true,\"R30\":true}";
            default -> "{\"R30\":true}";
        };
    }

    private void mailOwners(UUID tenantId, String subject, String body) {
        List<String> owners = jdbc.queryForList("""
                select u.email::text from memberships m join users u on u.id = m.user_id
                where m.tenant_id = ? and m.is_owner and m.status = 'ACTIVE'
                """, String.class, tenantId);
        for (String email : owners) {
            try {
                emailSender.send(EmailMessage.of(email.toLowerCase(Locale.ROOT), subject, body));
            }
            catch (RuntimeException ex) {
                log.warn("Subscription mail to a tenant owner could not be sent: {}", ex.getMessage());
            }
        }
    }
}
