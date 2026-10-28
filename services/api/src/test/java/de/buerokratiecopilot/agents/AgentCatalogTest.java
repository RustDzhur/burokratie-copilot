package de.buerokratiecopilot.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentManifest;
import de.buerokratiecopilot.agents.core.AgentRegistry;

/**
 * Guards the catalogue contract (docs/agents/RUNTIME.md): every manifest is complete, ids are
 * unique, categories are the allowed set, and the categories added for industry coverage are fully
 * declared (functions, triggers, inputs, systems, version, status).
 *
 * <p>The test reads the classpath the application reads, not a checked-in list, so a manifest that
 * is not shipped cannot pass. The {@link #IMPLEMENTED} set is the reviewed contract of the merged
 * catalogue: all 126 agents have an implementation, there is no concept left.</p>
 */
class AgentCatalogTest {

    /** The eight categories of the initial catalogue; their manifests predate the stricter schema. */
    private static final Set<String> LEGACY = Set.of("documents", "communication", "finance",
            "processes", "compliance", "analysis", "crm", "integration");

    /** The whole catalogue: 126 agents across the three implementation waves. */
    private static final Set<String> IMPLEMENTED = Set.of(
            "account-datev", "anomaly", "api-bridge", "approval", "audit-trail",
            "banking-fee-audit", "banking-payment-order", "banking-reconcile",
            "banking-statement-import", "budget-watch", "calendar-sync", "case-file", "cashflow",
            "chat-assist", "compliance-check", "crm-activity", "crm-contact", "crm-deal",
            "crm-followup", "crm-lead", "data-sync", "deadline-watch", "doc-archive", "doc-assign",
            "doc-datev", "doc-detect", "doc-extract", "doc-ocr", "doc-redact", "doc-scan",
            "doc-sign", "doc-validate", "dunning", "ecommerce-catalog-sync",
            "ecommerce-marketplace-feed", "ecommerce-order-sync", "ecommerce-payment-check",
            "ecommerce-price-monitor", "ecommerce-returns", "expense-sort", "forecast",
            "gdpr-guard", "healthcare-appointment", "healthcare-billing",
            "healthcare-documentation", "healthcare-recall", "healthcare-shift-handover",
            "hospitality-event-plan", "hospitality-menu-cost", "hospitality-no-show",
            "hospitality-reservation", "hospitality-shift-plan", "hr-leave-request",
            "hr-onboarding", "hr-payroll-data", "hr-recruiting-screening", "hr-time-tracking",
            "hr-training", "insight", "invoice-capture", "invoice-check", "it-access-review",
            "it-asset-inventory", "it-backup-check", "it-incident", "it-patch-window",
            "knowledge-base", "knowledge-kpi-report", "knowledge-meeting-memory",
            "knowledge-policy-digest", "knowledge-search-answer", "legal-audit-prep",
            "legal-contract-control", "legal-gdpr-request", "legal-litigation-file",
            "legal-nda-check", "legal-sanctions-kyc", "letter-draft", "logistics-customs",
            "logistics-delivery-tracking", "logistics-inventory-count", "logistics-order-intake",
            "logistics-route-planning", "logistics-supplier-scorecard", "mail-accept",
            "mail-reply", "mail-sort", "marketing-content-brief", "marketing-lead-nurture",
            "marketing-newsletter", "marketing-sea-campaign", "marketing-seo-audit",
            "marketing-social-calendar", "meeting-notes", "metric-collect", "monitor-systems",
            "payment-match", "phone-transcript", "production-material-plan",
            "production-quality-check", "production-quote-calc", "production-scheduling",
            "production-warranty", "production-work-order", "realestate-listing",
            "realestate-maintenance", "realestate-rent-dunning", "realestate-tenant-admin",
            "realestate-viewing", "reminder", "report-daily", "retention", "risk-flag",
            "sales-followup-cadence", "sales-offer-draft", "sales-pipeline-review",
            "sales-price-book", "sales-win-loss", "support-escalation", "support-faq-suggest",
            "support-nps", "support-review-response", "support-sla-report", "support-ticket",
            "task-dispatch", "workflow-route");

    private static final List<String> RESOURCE_PATHS = new ArrayList<>();
    private static List<AgentManifest> manifests;

    @BeforeAll
    static void load() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:agents/**/*.json");
        ObjectMapper mapper = new ObjectMapper();
        List<AgentManifest> loaded = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                loaded.add(mapper.readValue(in, AgentManifest.class));
                RESOURCE_PATHS.add(String.valueOf(resource.getFilename()));
            }
        }
        manifests = List.copyOf(loaded);
    }

    @Test
    void theCatalogueCoversAll126Agents() {
        assertThat(manifests).hasSize(126);
        assertThat(manifests).hasSize(RESOURCE_PATHS.size());
        assertThat(IMPLEMENTED).hasSize(126);
    }

    @Test
    void everyManifestHasTheRequiredIdentityAndDescription() {
        for (AgentManifest manifest : manifests) {
            assertThat(manifest.id()).as("id").matches("^[a-z0-9]+(-[a-z0-9]+)*$");
            assertThat(manifest.name()).as(manifest.id() + " name").isNotBlank();
            assertThat(manifest.category()).as(manifest.id() + " category").isNotBlank();
            assertThat(manifest.description()).as(manifest.id() + " description")
                    .isNotBlank().hasSizeGreaterThanOrEqualTo(40);
            assertThat(manifest.functions()).as(manifest.id() + " functions").isNotEmpty();
            assertThat(manifest.triggers()).as(manifest.id() + " triggers").isNotEmpty();
        }
    }

    @Test
    void idsAreUniqueAcrossTheWholeClasspath() {
        Set<String> ids = new HashSet<>();
        for (AgentManifest manifest : manifests) {
            assertThat(ids.add(manifest.id())).as("duplicate id " + manifest.id()).isTrue();
        }
    }

    @Test
    void everyCategoryIsAllowedAndEveryAllowedCategoryIsUsed() {
        Set<String> used = new HashSet<>();
        for (AgentManifest manifest : manifests) {
            assertThat(AgentRegistry.CATEGORIES).as(manifest.id() + " category")
                    .contains(manifest.category());
            used.add(manifest.category());
        }
        assertThat(used).containsExactlyInAnyOrderElementsOf(AgentRegistry.CATEGORIES);
    }

    @Test
    void theNewIndustryManifestsDeclareTheFullSchema() {
        for (AgentManifest manifest : manifests) {
            if (LEGACY.contains(manifest.category())) {
                continue;
            }
            String where = manifest.id() + " (" + manifest.category() + ")";
            assertThat(manifest.description()).as(where + " description").hasSizeGreaterThan(120);
            assertThat(manifest.functions()).as(where + " functions").hasSizeBetween(3, 6);
            assertThat(manifest.triggers()).as(where + " triggers").hasSizeGreaterThanOrEqualTo(2);
            assertThat(manifest.inputs()).as(where + " inputs").hasSizeGreaterThanOrEqualTo(2);
            assertThat(manifest.config()).as(where + " config")
                    .containsKeys("systems", "version", "status");
            assertThat(manifest.config().get("version")).as(where + " version").isEqualTo("1.0");
            assertThat(manifest.config().get("status")).as(where + " status")
                    .isIn("ACTIVE", "CONCEPT");
            assertThat((List<?>) manifest.config().get("systems")).as(where + " systems")
                    .isNotEmpty();
        }
    }

    /**
     * The merged catalogue is complete: every shipped manifest has a reviewed implementation, the
     * contract set and the classpath agree, and no manifest is still a concept.
     */
    @Test
    void everyCatalogueAgentIsImplementedAndActive() {
        List<String> ids = manifests.stream().map(AgentManifest::id).toList();
        assertThat(IMPLEMENTED).containsExactlyInAnyOrderElementsOf(ids);
        for (AgentManifest manifest : manifests) {
            Object status = manifest.config().get("status");
            assertThat(status == null || "ACTIVE".equals(status))
                    .as(manifest.id() + " is not ACTIVE (status " + status + ")").isTrue();
        }
    }
}
