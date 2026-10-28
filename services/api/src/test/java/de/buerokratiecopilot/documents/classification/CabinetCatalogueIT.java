package de.buerokratiecopilot.documents.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;

/** The catalogue every cabinet starts with: German document types, each with rules and a scenario. */
@SpringBootTest
@ActiveProfiles("test")
class CabinetCatalogueIT {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RuleBasedClassifier classifier;

    @Test
    @DisplayName("every shipped type has a recognition rule and a scenario, and names in three languages")
    void everyTypeIsUsable() {
        Integer shipped = jdbc.queryForObject("select count(*) from document_types where sort_order = 200", Integer.class);
        assertThat(shipped).isGreaterThanOrEqualTo(100);
        assertThat(jdbc.queryForList("""
                select code from document_types t where t.sort_order = 200
                  and (not exists (select 1 from document_type_rules r where r.document_type_id = t.id)
                    or not exists (select 1 from document_type_scenarios s where s.document_type_code = t.code)
                    or coalesce(t.name ->> 'de', '') = '' or coalesce(t.name ->> 'en', '') = ''
                    or coalesce(t.name ->> 'ru', '') = '')
                """, String.class)).isEmpty();
    }

    @Test
    @DisplayName("typical German letters are recognised on the fly, for a person and for a company")
    void recognisesTypicalLetters() {
        UUID tenant = jdbc.queryForObject("select id from tenants limit 1", UUID.class);
        // The rules are global; any tenant sees them.
        RequestContext.runAs(tenant, () -> {
            assertThat(codeOf(tenant, "Finanzamt Berlin\nEinkommensteuerbescheid 2024\nEinspruchsfrist einen Monat"))
                    .isEqualTo("einkommensteuerbescheid");
            assertThat(codeOf(tenant, "Kindergeld Bescheid Familienkasse Kindergeldnummer 123"))
                    .isEqualTo("kindergeldbescheid");
            assertThat(codeOf(tenant, "Umsatzsteuer-Voranmeldung Voranmeldungszeitraum 03/2026"))
                    .isEqualTo("umsatzsteuer_voranmeldung");
            assertThat(codeOf(tenant, "Prüfungsanordnung Betriebsprüfung Außenprüfung"))
                    .isEqualTo("betriebspruefung");
            assertThat(codeOf(tenant, "Pfändungs- und Überweisungsbeschluss Drittschuldner Gerichtsvollzieher"))
                    .isEqualTo("pfaendung");
            return null;
        });
    }

    private String codeOf(UUID tenant, String text) {
        var result = classifier.classify(new DocumentClassifier.Input(tenant, text, "x.pdf", null, null));
        return result.typeId() == null ? null
                : jdbc.queryForObject("select code from document_types where id = ?", String.class, result.typeId());
    }
}
