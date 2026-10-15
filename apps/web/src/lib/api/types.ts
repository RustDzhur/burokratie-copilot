/**
 * Data transfer objects shared with the backend.
 *
 * These types describe the JSON contract the web client expects. Field names
 * follow the API style (camelCase, ISO-8601 timestamps). Where docs/task.md only
 * defines the PostgreSQL model (§19) and the endpoint groups (§20), the shape
 * below is the proposed contract; the backend is the single source of truth and
 * can be generated from OpenAPI later without touching the components.
 *
 * Unknown enum values never break a screen: every status has a `…Meta` lookup
 * with a fallback (see `src/lib/format/status.ts`).
 */

export type IsoDateTime = string;
export type IsoDate = string;

export type DocumentStatus =
  'received' | 'processing' | 'needs_review' | 'processed' | 'failed' | 'archived';

export type DocumentChannel = 'upload' | 'email' | 'scan' | 'api' | 'mobile';

export interface DocumentSummary {
  readonly id: string;
  readonly title: string;
  readonly documentType: string;
  readonly status: DocumentStatus;
  readonly channel: DocumentChannel;
  readonly senderName?: string | null;
  readonly receivedAt: IsoDateTime;
  readonly confidence?: number | null;
  readonly retentionUntil?: IsoDate | null;
  readonly legalHold?: boolean;
  readonly ownerName?: string | null;
  readonly pageCount?: number | null;
  readonly sizeBytes?: number | null;
  /** The document type by name in every language, its code and the category the list is sorted into. */
  readonly typeNames?: Readonly<Record<string, string>> | null;
  readonly typeCode?: string | null;
  readonly category?: string | null;
  /** Whom the document is addressed to (a person of the household, the owner, the company). */
  readonly partyId?: string | null;
}

export interface PartySummary {
  readonly id: string;
  readonly kind: 'PERSON' | 'ORGANIZATION';
  readonly displayName: string;
  readonly relation: string;
  readonly birthDate: string | null;
  readonly aliases: string;
  readonly address: string;
  readonly identifiers: string;
  readonly primary: boolean;
  readonly notes: string | null;
  readonly documentCount: number;
}

export interface PartyInput {
  readonly displayName: string;
  readonly relation: string;
  readonly birthDate?: string | null;
  readonly aliases: string;
  readonly address: string;
  readonly identifiers: string;
  readonly notes?: string | null;
}

/** One document type of the catalogue the platform recognises, and the kinds of cabinet it is meant for. */
export interface CatalogueEntry {
  readonly code: string;
  readonly category: string;
  readonly names: Readonly<Record<string, string>>;
  readonly audiences: string;
}

export interface ExtractedField {
  readonly name: string;
  readonly label: string;
  readonly value?: string | null;
  readonly confidence: number;
  /** Human-readable evidence, e.g. "Seite 1, Zeile 12". */
  readonly source?: string | null;
  readonly confirmed?: boolean;
}

export interface DocumentRelation {
  readonly kind: 'case' | 'contract' | 'contact' | 'invoice' | 'task';
  readonly id: string;
  readonly label: string;
}

export interface DocumentHistoryEntry {
  readonly id: string;
  readonly at: IsoDateTime;
  readonly actor: string;
  readonly action: string;
  readonly result: 'success' | 'failure' | 'denied';
}

export interface DocumentDetail extends DocumentSummary {
  readonly checksum?: string | null;
  readonly mimeType?: string | null;
  readonly uploadedBy?: string | null;
  readonly fields?: readonly ExtractedField[];
  readonly relations?: readonly DocumentRelation[];
  readonly history?: readonly DocumentHistoryEntry[];
  readonly previewAvailable?: boolean;
  readonly processingError?: string | null;
}

export type CaseStatus = 'open' | 'in_progress' | 'waiting' | 'closed';

export interface CaseSummary {
  readonly id: string;
  readonly reference: string;
  readonly title: string;
  readonly status: CaseStatus;
  readonly caseType?: string | null;
  readonly documentCount: number;
  readonly updatedAt: IsoDateTime;
  readonly nextDeadline?: IsoDate | null;
  readonly responsibleName?: string | null;
  readonly missingDocuments?: boolean;
}

export type TaskStatus = 'todo' | 'doing' | 'blocked' | 'done';
export type TaskPriority = 'low' | 'normal' | 'high' | 'urgent';

export interface TaskSummary {
  readonly id: string;
  readonly title: string;
  readonly status: TaskStatus;
  readonly priority: TaskPriority;
  readonly dueDate?: IsoDate | null;
  readonly assigneeName?: string | null;
  readonly caseReference?: string | null;
  readonly overdue?: boolean;
}

export type ContractStatus = 'draft' | 'active' | 'expiring' | 'ended';

export interface ContractSummary {
  readonly id: string;
  readonly partner: string;
  readonly contractType: string;
  readonly status: ContractStatus;
  readonly startDate?: IsoDate | null;
  readonly endDate?: IsoDate | null;
  readonly noticePeriod?: string | null;
  readonly noticeDeadline?: IsoDate | null;
  readonly value?: number | null;
  readonly currency?: string | null;
}

export type InvoiceStatus = 'draft' | 'review' | 'approved' | 'paid' | 'overdue';
export type InvoiceDirection = 'incoming' | 'outgoing';

export interface InvoiceSummary {
  readonly id: string;
  readonly number: string;
  readonly counterparty: string;
  readonly direction: InvoiceDirection;
  readonly status: InvoiceStatus;
  readonly amount: number;
  readonly currency: string;
  readonly issueDate?: IsoDate | null;
  readonly dueDate?: IsoDate | null;
  readonly eRechnung?: boolean;
}

export type PaymentStatus = 'unmatched' | 'matched' | 'suggested' | 'ignored';

export interface PaymentSummary {
  readonly id: string;
  readonly bookingDate: IsoDate;
  readonly counterparty: string;
  readonly amount: number;
  readonly currency: string;
  readonly reference?: string | null;
  readonly status: PaymentStatus;
  readonly suggestion?: string | null;
}

export type ContactType = 'person' | 'organisation' | 'authority';

export interface ContactSummary {
  readonly id: string;
  readonly name: string;
  readonly contactType: ContactType;
  readonly email?: string | null;
  readonly phone?: string | null;
  readonly city?: string | null;
  readonly updatedAt: IsoDateTime;
}

export type MailStatus = 'new' | 'processed' | 'needs_reply';
export type MailFolder = 'inbox' | 'sent' | 'drafts' | 'archive';

export interface MailMessageSummary {
  readonly id: string;
  readonly subject: string;
  readonly from: string;
  readonly folder: MailFolder;
  readonly status: MailStatus;
  readonly receivedAt: IsoDateTime;
  readonly attachmentCount: number;
}

export interface TemplateSummary {
  readonly id: string;
  readonly name: string;
  readonly templateType: string;
  readonly status: 'draft' | 'published';
  readonly updatedAt: IsoDateTime;
  readonly body?: string | null;
  readonly placeholders?: readonly string[];
}

export type AutomationStatus = 'active' | 'paused' | 'error' | 'draft';

export interface AutomationSummary {
  readonly id: string;
  readonly name: string;
  readonly trigger: string;
  readonly status: AutomationStatus;
  readonly lastRunAt?: IsoDateTime | null;
  readonly lastRunResult?: 'success' | 'failed' | 'running' | null;
  readonly runsLast30Days?: number | null;
}

export type IntegrationStatus = 'connected' | 'disconnected' | 'error' | 'pending';

export interface IntegrationSummary {
  readonly id: string;
  readonly name: string;
  readonly category: 'bank' | 'mail' | 'storage' | 'calendar' | 'device' | 'identity';
  readonly status: IntegrationStatus;
  readonly lastSyncAt?: IsoDateTime | null;
  readonly configurationRequired?: boolean;
  readonly description?: string | null;
}

/** One entry of the integration catalogue: what exists, what it can do and what the owner has to bring. */
export interface IntegrationCatalogueEntry {
  readonly provider: string;
  readonly category: string;
  readonly name: string;
  readonly implemented: boolean;
  readonly capabilities: readonly string[];
  readonly ownerRequirements: readonly string[];
  readonly note: string | null;
}

/** A connected mailbox; the password is never part of it. */
export interface MailAccountSummary {
  readonly id: string;
  readonly name: string;
  readonly email: string;
  readonly provider: string;
  readonly status: 'CONNECTED' | 'DISCONNECTED' | 'ERROR' | string;
  readonly lastSyncAt: IsoDateTime | null;
  readonly lastError: string | null;
  readonly failures: number;
}

export interface MailCollectResult {
  readonly seen: number;
  readonly stored: number;
  readonly skipped: number;
  readonly documentsCreated: number;
}

export interface DeviceSummary {
  readonly id: string;
  readonly name: string;
  readonly kind: 'scanner' | 'multifunction' | 'bridge';
  readonly online: boolean;
  readonly lastSeenAt?: IsoDateTime | null;
}

export type MemberRole = 'owner' | 'admin' | 'member' | 'accountant' | 'auditor';
export type MemberStatus = 'active' | 'invited' | 'disabled';

export interface MemberSummary {
  readonly id: string;
  readonly name: string;
  readonly email: string;
  readonly role: MemberRole;
  readonly status: MemberStatus;
  readonly lastActiveAt?: IsoDateTime | null;
  readonly documentCount?: number | null;
}

export interface CalendarEvent {
  readonly id: string;
  readonly title: string;
  readonly date: IsoDate;
  readonly endDate?: IsoDate | null;
  readonly type: 'deadline' | 'appointment' | 'reminder';
  readonly caseReference?: string | null;
  readonly allDay?: boolean;
  /** Why the entry exists: what the platform read, from where, and how far to trust it. */
  readonly note?: string | null;
  readonly origin?: 'calendar-entry' | 'task-deadline' | 'case-due';
  readonly caseId?: string | null;
  readonly documentId?: string | null;
  readonly taskId?: string | null;
}

export interface ReportSeriesPoint {
  readonly period: string;
  readonly value: number;
}

export interface ReportResult {
  readonly type: string;
  readonly period: string;
  readonly generatedAt: IsoDateTime;
  readonly kpis: Readonly<Record<string, number>>;
  readonly series: readonly ReportSeriesPoint[];
}

export interface ArchiveEntry {
  readonly id: string;
  readonly title: string;
  readonly archivedAt?: IsoDateTime | null;
  readonly retentionUntil?: IsoDate | null;
  readonly legalHold: boolean;
  readonly status: 'retention_active' | 'retention_expired' | 'unknown';
}

export type NotificationCategory =
  'task' | 'deadline' | 'mail' | 'review' | 'approval' | 'integration' | 'automation' | 'archive' | 'radar';

export interface NotificationSummary {
  readonly id: string;
  readonly title: string;
  readonly body?: string | null;
  readonly category: NotificationCategory;
  readonly channels: readonly ('in_app' | 'email' | 'push')[];
  readonly read: boolean;
  readonly receivedAt: IsoDateTime;
  readonly link?: string | null;
  /** What kind of message this is, and the facts it is about; the screen writes the sentence from them. */
  readonly kind?: string;
  readonly facts?: Readonly<Record<string, unknown>>;
}

export type AuditResult = 'success' | 'failure' | 'denied';

export interface AuditEntry {
  readonly id: string;
  readonly timestamp: IsoDateTime;
  readonly actor: string;
  readonly action: string;
  readonly objectType: string;
  readonly objectLabel: string;
  readonly result: AuditResult;
  readonly correlationId?: string | null;
  readonly ipAddress?: string | null;
}

export interface DashboardMoney {
  readonly currency: string;
  readonly count: number;
  readonly open_amount: number;
  readonly overdue_count: number;
  readonly overdue_amount: number;
}

export interface DashboardSummary {
  readonly generatedAt: IsoDateTime;
  readonly counts: Readonly<Record<string, number>>;
  /** Only the parts the reader may see are present; amounts are per currency, never converted. */
  readonly business?: {
    readonly receivables?: readonly DashboardMoney[];
    readonly payables?: readonly DashboardMoney[];
    readonly tasksByPerson?: readonly { readonly user_id: string; readonly name: string; readonly open: number; readonly overdue: number }[];
  };
  readonly activity: readonly {
    readonly id: string;
    readonly at: IsoDateTime;
    readonly actor: string;
    readonly summary: string;
    readonly target?: string | null;
  }[];
  readonly automationStatus: readonly {
    readonly status: AutomationStatus;
    readonly count: number;
  }[];
  readonly integrationStatus: readonly {
    readonly status: IntegrationStatus;
    readonly count: number;
  }[];
}

export interface ReviewItem {
  readonly documentId: string;
  readonly title: string;
  readonly senderName?: string | null;
  readonly receivedAt: IsoDateTime;
  readonly fields: readonly ExtractedField[];
  readonly possibleDuplicateOf?: string | null;
}

export interface AssistantSource {
  readonly documentId: string;
  readonly title: string;
  readonly page?: number | null;
}

export interface AssistantAnswer {
  readonly answer: string;
  readonly sources: readonly AssistantSource[];
  readonly model: string;
  readonly correlationId?: string | null;
  readonly disclaimerRequired?: boolean;
}

export interface SessionUser {
  readonly id: string;
  readonly name: string;
  readonly email: string;
  readonly roles: readonly MemberRole[];
  readonly workspaceId: string;
  readonly workspaceName: string;
  readonly workspaceType: 'private' | 'family' | 'company';
  readonly avatarUrl?: string | null;
  /** False until the first-run wizard was finished; the sign-in sends such a person to it. */
  readonly onboardingCompleted?: boolean;
  /** The account requires a second factor and this person has none yet: they may only set one up. */
  readonly mfaEnrolmentRequired?: boolean;
  /** Runs the platform: gets the /admin area. Given by the server's configuration, never by the interface. */
  readonly platformAdmin?: boolean;
  readonly subscription?: SubscriptionInfo | null;
}

export type SubscriptionStatus = 'TRIAL' | 'ACTIVE' | 'EXPIRED' | 'SUSPENDED';

/** Where a workspace stands: free trial, paid, expired (data kept for a while) or suspended. */
export interface SubscriptionInfo {
  readonly planCode: string;
  readonly planName: string;
  /** 0 Free, 1 Personal, 2 Business, 3 Professional: what the menu follows (the server enforces it). */
  readonly planTier?: number;
  readonly analysesPerMonth?: number | null;
  readonly status: SubscriptionStatus;
  readonly accessUntil: IsoDateTime;
  readonly retentionUntil?: IsoDateTime | null;
  readonly daysLeft: number;
  readonly blocked: boolean;
  readonly dataChoice?: 'KEEP' | 'DELETE' | null;
  readonly retentionDays: number;
}

export interface PlanSummary {
  readonly code: string;
  readonly name: string;
  readonly priceCents: number;
  readonly currency: string;
  readonly accountType?: string;
  readonly active?: boolean;
  readonly tier?: number;
  /** The cabinets the plan is offered to: private, family, company. */
  readonly audiences?: readonly string[];
  readonly analysesPerMonth?: number | null;
  readonly features?: readonly string[];
}

export interface BillingState extends SubscriptionInfo {
  readonly owner: boolean;
  readonly invoiceAvailable: boolean;
  readonly cryptoAvailable: boolean;
  readonly plans: readonly PlanSummary[];
}

export interface BillingInvoice {
  readonly id: string;
  readonly number: string;
  readonly planCode: string;
  readonly months: number;
  readonly amountCents: number;
  readonly status: 'ISSUED' | 'PAID' | 'CANCELLED';
  readonly method: 'INVOICE' | 'CRYPTO';
  readonly reference: string;
  readonly issuedAt: IsoDateTime;
  readonly dueAt: IsoDateTime;
  readonly paidAt?: IsoDateTime | null;
  readonly cryptoAsset?: string | null;
  readonly cryptoNetwork?: string | null;
  readonly cryptoAddress?: string | null;
  readonly cryptoAmount?: string | null;
  /** Admin lists only. */
  readonly tenantName?: string | null;
  readonly customerName?: string | null;
  readonly customerEmail?: string | null;
}

export interface InvoiceRequest {
  readonly method: 'INVOICE' | 'CRYPTO';
  readonly months: number;
  readonly customerName: string;
  readonly customerAddress: string;
  readonly customerCountry: string;
  readonly customerVatId?: string;
}

export interface AdminOverview {
  readonly tenants: number;
  readonly byStatus: readonly { readonly status: SubscriptionStatus; readonly count: number }[];
  readonly monthlyRecurringCents: number;
  readonly trialEndingSoon: number;
  readonly openInvoices: number;
  readonly payeeConfigured: boolean;
  readonly cryptoConfigured: boolean;
}

export interface AdminTenant {
  readonly id: string;
  readonly name: string;
  readonly accountType: string;
  readonly createdAt: IsoDateTime;
  readonly planCode: string;
  readonly status: SubscriptionStatus;
  readonly accessUntil: IsoDateTime;
  readonly retentionUntil?: IsoDateTime | null;
  readonly dataChoice?: 'KEEP' | 'DELETE' | null;
  readonly ownerEmail?: string | null;
  readonly members: number;
}

export interface AdminService {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly detail: string;
  readonly setting: string;
}

export interface SessionInfo {
  readonly id: string;
  readonly device: string;
  readonly location?: string | null;
  readonly lastActiveAt: IsoDateTime;
  readonly current: boolean;
}

export interface SecuritySettings {
  readonly mfaEnabled: boolean;
  readonly passwordChangedAt?: IsoDateTime | null;
  readonly sessions: readonly SessionInfo[];
  readonly policy: Readonly<Record<string, string | number | boolean>>;
}

export interface NotificationPreferences {
  readonly categories: Readonly<Record<NotificationCategory, 'immediate' | 'digest' | 'off'>>;
  readonly channels: Readonly<Record<'in_app' | 'email' | 'push', boolean>>;
}

export interface AccessibilityPreferences {
  readonly theme?: 'light' | 'dark' | 'system';
  readonly contrast?: 'normal' | 'high';
  readonly textScale?: number;
  readonly motion?: 'auto' | 'reduced';
  readonly effects?: 'full' | 'reduced';
  readonly colorCues?: 'off' | 'patterns';
  readonly density?: 'comfortable' | 'compact';
}

/** What can be deleted from a list. */
export type DeletionKind =
  | 'DOCUMENT'
  | 'TASK'
  | 'CASE'
  | 'CONTACT'
  | 'CONTRACT'
  | 'TEMPLATE'
  | 'MAIL'
  | 'CALENDAR_EVENT'
  | 'NOTIFICATION'
  | 'AUTOMATION';

/** A received message for reading: the content is plain text only. */
export interface MailMessageDetail {
  readonly id: string;
  readonly subject: string;
  readonly from_name: string | null;
  readonly from_address: string | null;
  readonly to_addresses: string | null;
  readonly direction: string;
  readonly attachment_count: number;
  readonly moment: string;
  readonly is_suspicious: boolean;
  readonly body: string;
}

/** The person's own Telegram connection. */
export interface TelegramStatus {
  readonly configured: boolean;
  readonly botUsername: string;
  readonly linked: boolean;
  readonly enabled: boolean;
  readonly level: 'ALL' | 'IMPORTANT';
  readonly deepLink: string | null;
}

/** A date of a case with where it comes from; `confirmation` says whether a person checked it. */
export interface CaseDeadline {
  readonly id: string;
  readonly type: string;
  readonly dueDate: string | null;
  readonly source: 'DOCUMENT' | 'RULE' | 'MANUAL' | 'WORKFLOW' | 'AI';
  readonly confirmation: 'CONFIRMED' | 'UNCONFIRMED';
  readonly sourceDocumentId: string | null;
  readonly sourcePage: number | null;
  readonly sourceSnippet: string | null;
  readonly ruleVersion: number | null;
  readonly note: string | null;
}

/** A case with what belongs to it: its documents, its history, what is still missing. */
export interface CaseDetail {
  readonly id: string;
  readonly reference: string;
  readonly title: string;
  readonly status: string;
  readonly priority: string | null;
  readonly typeName: string | null;
  readonly description: string | null;
  readonly dueAt: string | null;
  readonly openedAt: string | null;
  readonly responsibleName: string | null;
  readonly documents: readonly {
    readonly documentId: string;
    readonly filename: string;
    readonly processingStatus: string;
    readonly primary: boolean;
  }[];
  readonly timeline: readonly {
    readonly id: string;
    readonly type: string;
    readonly summary: string | null;
    readonly occurredAt: string;
  }[];
  readonly requiredDocuments: readonly string[];
}


/* -------------------------------------------------------------------------- */
/* Radar                                                                       */
/* -------------------------------------------------------------------------- */

export type RadarStatus = 'NEW' | 'UNDER_REVIEW' | 'CONFIRMED' | 'DISMISSED' | 'SNOOZED' | 'RESOLVED' | 'REOPENED';
export type RadarSeverity = 'INFO' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

/** A signal the platform raised. It is a hint for a person to judge, never a verdict. */
export interface RadarFinding {
  readonly id: string;
  readonly type: string;
  readonly ruleId: string;
  readonly ruleVersion: number;
  readonly title: string;
  readonly description: string | null;
  readonly status: RadarStatus;
  readonly severity: RadarSeverity;
  readonly confidence: number | null;
  readonly detectedAt: string;
  readonly lastEvaluatedAt: string;
  readonly caseId: string | null;
  readonly contactId: string | null;
  readonly contractId: string | null;
  readonly assignedTo: string | null;
  readonly snoozedUntil: string | null;
  readonly resolvedAt: string | null;
  readonly resolutionReason: string | null;
  readonly explanation: string | null;
  readonly reopenCount: number;
  readonly evidenceCount: number;
  readonly reasonCodes: readonly string[];
  /** The moves the backend allows from the current status; the screen offers exactly these. */
  readonly allowedStatuses: readonly RadarStatus[];
}

export interface RadarEvidence {
  readonly id: string;
  readonly role: 'PRIMARY' | 'COMPARISON' | 'CONTEXT';
  readonly documentId: string | null;
  readonly documentName: string | null;
  readonly page: number | null;
  readonly snippet: string | null;
}

export interface RadarEvent {
  readonly id: string;
  readonly eventType: string;
  readonly fromStatus: string | null;
  readonly toStatus: string | null;
  readonly reason: string | null;
  readonly createdAt: string;
}

export interface RadarFindingDetail extends RadarFinding {
  readonly evidence: readonly RadarEvidence[];
  readonly history: readonly RadarEvent[];
}

export interface RadarSummary {
  readonly byStatus: Readonly<Record<string, number>>;
  readonly openBySeverity: Readonly<Record<string, number>>;
  readonly recentlyResolved: readonly { readonly id: string; readonly title: string; readonly severity: RadarSeverity; readonly resolvedAt: string }[];
}

export type RadarAction =
  | { readonly kind: 'review' }
  | { readonly kind: 'confirm'; readonly reason?: string }
  | { readonly kind: 'dismiss'; readonly reason: string }
  | { readonly kind: 'resolve'; readonly reason: string }
  | { readonly kind: 'snooze'; readonly until: string; readonly reason?: string }
  | { readonly kind: 'reopen'; readonly reason?: string }
  | { readonly kind: 'assign'; readonly userId: string }
  | { readonly kind: 'create-case' }
  | { readonly kind: 're-evaluate' };


/* -------------------------------------------------------------------------- */
/* Approvals and the case workspace                                            */
/* -------------------------------------------------------------------------- */

/** An open approval with what a person needs to decide it. */
export interface ApprovalRequestItem {
  readonly id: string;
  readonly title: string;
  readonly justification: string | null;
  readonly subjectType: string;
  readonly approvalType: string;
  readonly requiredRole: string | null;
  readonly requestedByName: string | null;
  readonly expiresAt: string | null;
  readonly createdAt: string;
  /** True when the request is bound to the content (a change of the material invalidates it). */
  readonly bound: boolean;
  readonly case: { readonly id: string; readonly reference: string; readonly title: string } | null;
  /** The material to decide on: for a payment the amount and recipient, for a letter the subject and the beginning of the text. */
  readonly material: Readonly<Record<string, unknown>>;
  readonly warnings: readonly ('BANK_DETAILS_CHANGED' | 'OPEN_RADAR_FINDINGS')[];
  readonly mayDecide: boolean;
}

export type ApprovalDecision =
  | { readonly kind: 'approve'; readonly note?: string }
  | { readonly kind: 'reject'; readonly note: string }
  | { readonly kind: 'changes'; readonly note: string };

export interface CompletionCriterion {
  readonly id: string;
  readonly kind: string;
  readonly description: string;
  readonly status: 'OPEN' | 'MET' | 'WAIVED';
  readonly metAt: string | null;
  readonly waivedReason: string | null;
}

export interface PlanStep {
  readonly key: string;
  readonly action: string;
  readonly title: string;
  readonly status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'WAITING' | 'COMPENSATED';
  readonly level: number;
  readonly auto: boolean;
  readonly dependsOn: readonly string[];
  readonly blockedBy: readonly string[];
  readonly ready: boolean;
  readonly evidenceRequired: boolean;
  readonly errorMessage: string | null;
}

export interface CaseWorkspace {
  readonly goal: string | null;
  readonly nextAction: string | null;
  readonly criteria: readonly CompletionCriterion[];
  readonly plan: { readonly status: string | null; readonly steps: readonly PlanStep[] };
}

export type PlanStepAction =
  | { readonly kind: 'start' }
  | { readonly kind: 'complete'; readonly note?: string }
  | { readonly kind: 'fail'; readonly reason: string }
  | { readonly kind: 'skip'; readonly reason: string };


/* -------------------------------------------------------------------------- */
/* Business memory: contact profile                                            */
/* -------------------------------------------------------------------------- */

export interface ContactProfile {
  readonly contact: { readonly id: string; readonly name: string; readonly email: string | null; readonly phone: string | null; readonly isCustomer: boolean; readonly isSupplier: boolean };
  readonly identifiers: readonly { readonly id: string; readonly kind: string; readonly value: string }[];
  readonly documents: readonly { readonly relationshipId: string; readonly documentId: string; readonly name: string; readonly status: 'SUGGESTED' | 'CONFIRMED'; readonly score: number | null }[];
  readonly contracts: readonly { readonly id: string; readonly title: string; readonly status: string; readonly noticeDeadline: string | null }[];
  readonly invoices: readonly { readonly id: string; readonly number: string | null; readonly gross: string | null; readonly currency: string; readonly status: string }[];
  readonly cases: readonly { readonly id: string; readonly reference: string; readonly title: string; readonly status: string }[];
  readonly openFindings: readonly { readonly id: string; readonly title: string; readonly severity: RadarSeverity }[];
}

export interface TimelineEntry {
  readonly source: string;
  readonly id: string;
  readonly eventType: string;
  readonly title: string;
  readonly eventTime: string;
  readonly recordedAt: string;
}


export interface RelationshipItem {
  readonly id: string;
  readonly source: 'MATCH' | 'RELATION';
  readonly type: string;
  readonly status: 'SUGGESTED' | 'CONFIRMED' | 'REJECTED';
  readonly documentId: string | null;
  readonly documentName: string | null;
  readonly reason: string | null;
}

export interface ContractMemory {
  readonly id: string;
  readonly title: string;
  readonly status: string;
  readonly partnerName: string | null;
  readonly startDate: string | null;
  readonly endDate: string | null;
  readonly cost: string | null;
  readonly obligations: readonly { readonly id: string; readonly title: string; readonly dueDate: string | null; readonly status: string }[];
}

export interface InvoiceMemory {
  readonly id: string;
  readonly number: string | null;
  readonly supplierName: string | null;
  readonly gross: string | null;
  readonly currency: string;
  readonly status: string;
  readonly dueDate: string | null;
  readonly documentId: string | null;
  readonly bankDetailsChanged: boolean;
  readonly flags: readonly string[];
}


/** The type of a claim in an explanation of a document: what stands in it, what is a reading of it, what is not known. */
export type ClaimType = 'SOURCE_FACT' | 'AI_INTERPRETATION' | 'USER_CONFIRMED' | 'UNKNOWN' | 'REQUIRES_EXPERT_REVIEW';

export interface ExplanationClaim {
  readonly text: string;
  readonly type: ClaimType;
  readonly quote?: string | null;
  readonly page?: number | null;
  readonly downgraded?: boolean;
}

export interface ExplanationBlock {
  readonly key: string;
  readonly claims: readonly ExplanationClaim[];
}

/** An explanation (ten blocks) or a translation (text) of a document, as stored; it never replaces the original. */
export interface DocumentExplanationContent {
  readonly language: string;
  readonly disclaimer: string;
  readonly blocks?: readonly ExplanationBlock[];
  readonly text?: string;
  readonly machineTranslation?: boolean;
  readonly simpleLanguage?: boolean;
  readonly hiddenInstructionRemoved?: boolean;
  readonly withheldCount?: number;
}

export interface StoredExplanation {
  readonly id: string;
  readonly content: DocumentExplanationContent;
  readonly createdAt: string;
  /** False when the document was read again after the explanation was written. */
  readonly current: boolean;
}

export interface ExplanationOutcome {
  readonly kind: 'EXPLAINED' | 'TRANSLATED' | 'NO_TEXT' | 'UNAVAILABLE' | 'REJECTED';
  readonly reason: string | null;
}


/** One thing that is recorded to happen; it always names its source, and says what is not known about it. */
export interface FutureEvent {
  readonly id: string;
  readonly kind:
    | 'DEADLINE'
    | 'TASK'
    | 'OBLIGATION'
    | 'CONTRACT_NOTICE'
    | 'CONTRACT_END'
    | 'INVOICE_DUE'
    | 'CASE_DUE'
    | 'EXPECTED_REPLY';
  readonly date: IsoDate | null;
  readonly title: string;
  readonly amount: number | string | null;
  readonly currency: string | null;
  readonly certainty: string;
  readonly unknown: readonly string[];
  readonly source: { readonly type: string; readonly id: string };
  readonly documentId: string | null;
  readonly caseId: string | null;
  readonly contractId: string | null;
}

export interface FutureTimeline {
  readonly horizon: 30 | 60 | 90;
  readonly from: IsoDate;
  readonly to: IsoDate;
  readonly events: readonly FutureEvent[];
  readonly undated: readonly FutureEvent[];
}

export type ScenarioType = 'NOTICE_MISSED' | 'PRICE_CHANGE' | 'PAYMENT_LATE';

export interface ScenarioRequest {
  readonly type: ScenarioType;
  readonly contractId?: string;
  readonly invoiceId?: string;
  readonly percent?: number;
  readonly days?: number;
}

/** The answer to a what-if: computed from recorded values, or the list of what is missing. Nothing is changed. */
export interface ScenarioResult {
  readonly type: ScenarioType;
  readonly status: 'COMPUTED' | 'INSUFFICIENT_DATA';
  readonly readOnly: true;
  readonly missing?: readonly string[];
  readonly baseline?: Readonly<Record<string, unknown>>;
  readonly alternative?: Readonly<Record<string, unknown>>;
  readonly difference?: number | string;
  readonly currency?: string;
  readonly assumptions?: readonly string[];
  readonly unknown?: readonly string[];
}


/** One threshold or switch a workspace may tune, with the platform default beside the value in force. */
export interface RuleSetting {
  readonly key: string;
  readonly category: string;
  readonly description: Readonly<Record<string, string>>;
  readonly type: 'NUMBER' | 'BOOLEAN' | 'STRING';
  readonly min: number | null;
  readonly max: number | null;
  readonly allowed: readonly string[] | null;
  readonly default: unknown;
  readonly value: unknown;
  readonly overridden: boolean;
  readonly version: number;
}

export interface RuleSettingVersion {
  readonly version: number;
  readonly previousValue: string | null;
  readonly newValue: string;
  readonly reason: string | null;
  readonly changedAt: string;
}

/** A case a document is linked to; `linkOrigin` says whether a person or a rule made the link. */
export interface DocumentCase {
  readonly id: string;
  readonly title: string;
  readonly status: string;
  readonly link_origin: 'MANUAL' | 'RULE' | 'AI' | 'IMPORT';
  readonly is_primary: boolean;
  readonly confirmed_at: string | null;
}
