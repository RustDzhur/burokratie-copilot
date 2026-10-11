package de.buerokratiecopilot.contracts.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A clause of a contract, as read and as understood — two separate things (spec §13.1).
 *
 * <p>The schema separates them, and this class keeps the separation honest:</p>
 * <ul>
 *   <li><strong>detected</strong>: {@code clauseText}, {@code sourcePage}, {@code sourceSnippet},
 *       {@code confidence}. What the document says, and where.</li>
 *   <li><strong>interpreted</strong>: {@code interpretation}, {@code interpretationSource},
 *       {@code interpretationVerifiedBy}. What somebody concluded from it, on what basis, and who
 *       checked it.</li>
 * </ul>
 *
 * <p>An interpretation without a source is refused, because it would be a legal conclusion the platform
 * invented. The specification is explicit that the difference between detected text and legal
 * interpretation must be visible, and a user who cannot tell them apart will treat a guess as advice.</p>
 */
@Entity
@Table(name = "contract_clauses")
public class ContractClause {

    /** The clause types the schema allows; a parallel vocabulary here would produce refused rows. */
    public enum ClauseType {
        TERM, NOTICE_PERIOD, AUTO_RENEWAL, PRICE, PRICE_ADJUSTMENT, OBLIGATION, TERMINATION, LIABILITY,
        WARRANTY, DATA_PROTECTION, SERVICE_SCOPE, PAYMENT_TERMS, OTHER
    }

    /**
     * Who produced an interpretation.
     *
     * <p>The schema allows exactly three values and, more importantly, states the rule they encode: an
     * interpretation either comes from a RULE, or it must be verified. A person's reading is therefore
     * recorded as theirs and confirmed in the same act; only a rule-based reading may await
     * confirmation. The database enforces this, and the code follows it rather than working around
     * it.</p>
     */
    public enum InterpretationSource {
        NONE, RULE, HUMAN
    }

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "clause_key", nullable = false, length = 80)
    private String clauseKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "clause_type", nullable = false, length = 30)
    private ClauseType clauseType = ClauseType.OTHER;

    private String title;

    /** The words of the document, unchanged. Never a summary. */
    @Column(name = "clause_text", nullable = false)
    private String clauseText;

    @Column(name = "source_page")
    private Integer sourcePage;

    @Column(name = "source_snippet")
    private String sourceSnippet;

    /** How sure the extraction was; {@code null} when a person entered the text. */
    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "is_critical", nullable = false)
    private boolean critical;

    /** What somebody concluded. {@code null} until a rule or a person provides a basis. */
    private String interpretation;

    /** Who produced the interpretation; the schema allows NONE, RULE or HUMAN and nothing else. */
    @Enumerated(EnumType.STRING)
    @Column(name = "interpretation_source", nullable = false, length = 20)
    private InterpretationSource interpretationSource = InterpretationSource.NONE;

    @Column(name = "interpretation_verified_by")
    private UUID interpretationVerifiedBy;

    @Column(name = "interpretation_verified_at")
    private Instant interpretationVerifiedAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ContractClause() {
        // for JPA
    }

    public ContractClause(UUID id, UUID tenantId, UUID contractId, String clauseKey, ClauseType clauseType,
                          String clauseText) {
        this.id = id;
        this.tenantId = tenantId;
        this.contractId = contractId;
        this.clauseKey = clauseKey;
        this.clauseType = clauseType;
        this.clauseText = clauseText;
    }

    public UUID getId() {
        return id;
    }

    public UUID getContractId() {
        return contractId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getClauseKey() {
        return clauseKey;
    }

    public ClauseType getClauseType() {
        return clauseType;
    }

    public void setClauseType(ClauseType clauseType) {
        this.clauseType = clauseType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getClauseText() {
        return clauseText;
    }

    public void setClauseText(String clauseText) {
        this.clauseText = clauseText;
        this.updatedAt = Instant.now();
    }

    public Integer getSourcePage() {
        return sourcePage;
    }

    public void setSourcePage(Integer sourcePage) {
        this.sourcePage = sourcePage;
    }

    public String getSourceSnippet() {
        return sourceSnippet;
    }

    public void setSourceSnippet(String sourceSnippet) {
        this.sourceSnippet = sourceSnippet;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public boolean isCritical() {
        return critical;
    }

    public void setCritical(boolean critical) {
        this.critical = critical;
    }

    public String getInterpretation() {
        return interpretation;
    }

    public String getInterpretationSource() {
        return interpretationSource == null ? null : interpretationSource.name();
    }

    public UUID getInterpretationVerifiedBy() {
        return interpretationVerifiedBy;
    }

    public Instant getInterpretationVerifiedAt() {
        return interpretationVerifiedAt;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean hasInterpretation() {
        return interpretation != null && !interpretation.isBlank();
    }

    public boolean isInterpretationVerified() {
        return interpretationVerifiedAt != null && interpretationVerifiedBy != null;
    }

    /**
     * Records an interpretation together with who produced it.
     *
     * <p>A {@code HUMAN} reading must be asserted by a person and is therefore stored as verified by
     * them — the schema will not accept it otherwise. A {@code RULE} reading may stand unconfirmed
     * until somebody checks it, which is the state the review list is for.</p>
     *
     * @param authorUserId the person asserting the interpretation; required for {@code HUMAN}
     * @throws IllegalArgumentException when the basis or the statement is missing, or when a human
     *                                  reading arrives without an author
     */
    public void interpret(String interpretation, InterpretationSource source, UUID authorUserId) {
        if (interpretation == null || interpretation.isBlank()) {
            throw new IllegalArgumentException("An interpretation needs a statement");
        }
        if (source == null || source == InterpretationSource.NONE) {
            throw new IllegalArgumentException(
                    "An interpretation needs a basis: a rule or the person who drew the conclusion. "
                            + "Without one it would be the platform's own legal opinion");
        }
        if (source == InterpretationSource.HUMAN && authorUserId == null) {
            throw new IllegalArgumentException(
                    "A conclusion drawn by a person needs that person: it is their reading, not the "
                            + "platform's");
        }
        this.interpretation = interpretation.strip();
        this.interpretationSource = source;
        if (source == InterpretationSource.HUMAN) {
            // The schema ties a human reading to a verification, and a person asserting it IS the
            // verification.
            this.interpretationVerifiedBy = authorUserId;
            this.interpretationVerifiedAt = Instant.now();
        }
        else {
            this.interpretationVerifiedBy = null;
            this.interpretationVerifiedAt = null;
        }
        this.updatedAt = Instant.now();
    }

    /** A person confirms that the interpretation matches the clause. */
    public void verifyInterpretation(UUID userId) {
        if (!hasInterpretation()) {
            throw new IllegalStateException("There is no interpretation to verify");
        }
        this.interpretationVerifiedBy = userId;
        this.interpretationVerifiedAt = Instant.now();
    }
}
