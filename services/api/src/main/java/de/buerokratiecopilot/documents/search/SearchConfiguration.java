package de.buerokratiecopilot.documents.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chooses the semantic search implementation (spec §10.5, Q-06).
 *
 * <p>Whether vectors can be stored is a property of the database, not of the configuration file, so it
 * is asked of the database once at startup. The answer is logged, because an operator who expects
 * semantic search and does not get it should find out from the log rather than from a user.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SearchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SearchConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(EmbeddingProvider.class)
    public EmbeddingProvider embeddingProvider() {
        // The model-backed implementation belongs to the AI layer (task E-005). Until it exists, the
        // platform must say that no model is configured instead of pretending vectors exist.
        log.info("No embedding model is configured: semantic search will be reported as unavailable");
        return new NoEmbeddingProvider();
    }

    @Bean
    @ConditionalOnMissingBean(SemanticSearchPort.class)
    public SemanticSearchPort semanticSearchPort(JdbcTemplate jdbc, EmbeddingProvider embeddings) {
        boolean vectorStorePresent = hasVectorStore(jdbc);
        PgVectorSemanticSearch port = new PgVectorSemanticSearch(jdbc, embeddings, vectorStorePresent);
        log.info("Semantic search status: {} - {}", port.status(), port.statusReason());
        return port;
    }

    /**
     * Checks for the vector table rather than for the extension.
     *
     * <p>Migration V14 creates the table only when the extension could be installed, so the table's
     * presence is exactly the condition the search depends on.</p>
     */
    private static boolean hasVectorStore(JdbcTemplate jdbc) {
        try {
            String table = jdbc.queryForObject("select to_regclass('document_embeddings')::text",
                    String.class);
            return table != null && !table.isBlank();
        }
        catch (RuntimeException ex) {
            log.warn("The vector store could not be checked; semantic search is reported as unavailable: {}",
                    ex.getMessage());
            return false;
        }
    }
}
