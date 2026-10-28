package de.buerokratiecopilot.ai.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.ai.rag.RetrievalService.Passage;
import de.buerokratiecopilot.ai.rag.RetrievalService.Result;
import de.buerokratiecopilot.ai.rag.RetrievalService.Source;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Answers a question from the tenant's own documents (spec §25, E-008 acceptance).
 *
 * <p>Two rules, and both are about refusing to produce something that looks like an answer.</p>
 *
 * <p><strong>No passages means no answer.</strong> The acceptance criterion says the assistant reports
 * that there is no data when nothing relevant was found. A model asked to answer anyway will answer
 * anyway — from its training, from the question's wording, from nowhere — and the result reads exactly
 * like an answer that came from the files. So the platform does not ask: it returns "not found", with
 * what it searched for, and stops.</p>
 *
 * <p><strong>A citation has to point at a passage that was actually found.</strong> An answer whose
 * sources are invented is worse than no answer, because it is checkable in principle and wrong in fact.
 * Every citation is therefore matched against the retrieval set before the answer leaves this class, and
 * one that does not match is removed and reported — not quietly dropped.</p>
 *
 * <p>Nothing here writes the answer's prose. That is the model's work, and this class only decides
 * whether there is anything to answer from and whether the sources hold up.</p>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final RetrievalService retrieval;

    public RagService(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    /** A citation the model offered. */
    public record Citation(UUID documentId, Integer pageNumber, String fieldKey, String claim) {
    }

    /** What came out. */
    public record Answer(String kind, String message, List<Passage> sources, List<Citation> rejected,
                         List<Map<String, Object>> citations, String query) {

        /** Whether there was anything to answer from. */
        public boolean hasData() {
            return "ANSWER".equals(kind);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("message", message);
            map.put("query", query);
            map.put("found", sources.size());
            map.put("sources", sources.stream().map(Passage::asMap).toList());
            map.put("citations", citations);
            map.put("rejectedCitations", rejected.size());
            return map;
        }
    }

    /**
     * Prepares an answer.
     *
     * @param claims the sentences the model wants to say, each with the source it claims to have used
     */
    public Answer prepare(UUID tenantId, String query, List<Citation> claims, List<UUID> documentTypeIds,
                          int limit) {
        Result found = retrieval.search(tenantId, query, documentTypeIds, limit);

        if (found.isEmpty()) {
            // The acceptance criterion. The message says what was searched for, so the user can tell
            // "no such document" from "I did not understand the question".
            log.info("RAG found nothing for tenant {} and query '{}'", tenantId, query);
            return new Answer("NO_DATA",
                    "Zu dieser Frage wurden in den eigenen Unterlagen keine passenden Stellen gefunden. "
                            + "Die Plattform antwortet nicht ohne Belege; gesucht wurde nach: \"" + query
                            + "\".",
                    List.of(), List.of(), List.of(), query);
        }

        List<Citation> accepted = new ArrayList<>();
        List<Citation> rejected = new ArrayList<>();
        for (Citation claim : claims == null ? List.<Citation>of() : claims) {
            if (isSupported(claim, found.passages())) {
                accepted.add(claim);
            }
            else {
                // Removed and counted rather than dropped in silence: a source that could not be found
                // is something the user should be told about.
                rejected.add(claim);
                log.warn("RAG rejected a citation for tenant {}: document {} page {}", tenantId,
                        claim.documentId(), claim.pageNumber());
            }
        }

        List<Map<String, Object>> citations = new ArrayList<>();
        for (Citation claim : accepted) {
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("documentId", claim.documentId().toString());
            citation.put("pageNumber", claim.pageNumber());
            citation.put("claim", claim.claim());
            found.passages().stream()
                    .filter(passage -> passage.documentId().equals(claim.documentId())
                            && java.util.Objects.equals(passage.pageNumber(), claim.pageNumber())
                            && (claim.fieldKey() == null
                                    || claim.fieldKey().equals(passage.fieldKey())))
                    .findFirst()
                    .ifPresent(passage -> citation.put("citation", passage.citation()));
            citations.add(citation);
        }

        return new Answer("ANSWER",
                "Antwort auf Grundlage von " + found.passages().size() + " Stelle(n) in den eigenen "
                        + "Unterlagen.",
                found.passages(), List.copyOf(rejected), List.copyOf(citations), query);
    }

    /**
     * Whether a claim's source is among the passages that were found.
     *
     * <p>The document has to be in the retrieval set, and so does the page when one was named. A claim
     * about a page that exists but was not retrieved is still unsupported: the model did not read it.</p>
     */
    private boolean isSupported(Citation claim, List<Passage> passages) {
        if (claim == null || claim.documentId() == null) {
            return false;
        }
        return passages.stream().anyMatch(passage -> passage.documentId().equals(claim.documentId())
                && (claim.pageNumber() == null || claim.pageNumber().equals(passage.pageNumber()))
                && (claim.fieldKey() == null || claim.fieldKey().equals(passage.fieldKey())));
    }

    /**
     * Checks a finished answer against the passages it was built from.
     *
     * <p>Used before an answer reaches a user, and by the test that verifies the acceptance criterion: a
     * check only the test performs cannot protect production.</p>
     */
    public List<Citation> unsupported(List<Citation> claims, List<Passage> passages) {
        List<Citation> unsupported = new ArrayList<>();
        for (Citation claim : claims == null ? List.<Citation>of() : claims) {
            if (!isSupported(claim, passages)) {
                unsupported.add(claim);
            }
        }
        return unsupported;
    }

    /** The query a caller would send, so the platform's searches can be reproduced. */
    public String describeQuery(String query, List<UUID> documentTypeIds) {
        return query + (documentTypeIds == null || documentTypeIds.isEmpty()
                ? " (alle Dokumenttypen)"
                : " (" + documentTypeIds.size() + " Dokumenttypen)");
    }

    /** Refuses a question that is not a question, rather than searching for nothing. */
    public void requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ohne Frage wird nicht gesucht; eine leere Suche liefert keine Belege");
        }
    }

    /** The sources of an answer, as a list of citations a user can read. */
    public List<String> sourceList(Answer answer) {
        return answer.sources().stream()
                .map(passage -> passage.citation()
                        + (passage.source() == Source.EXTRACTED_FIELD ? "" : ""))
                .toList();
    }
}
