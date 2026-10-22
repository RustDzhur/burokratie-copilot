export type { GeneratedDocumentSummary, GeneratedDocumentReadiness } from './resources';
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

export interface DocumentSender {
  readonly kind?: string;
  readonly address?: string;
  readonly phone?: string;
  readonly email?: string;
  readonly website?: string;
  readonly vatId?: string;
  readonly iban?: string;
  /** LETTER read by the platform, MAIL from the mail, PERSON typed by a person. */
  readonly source?: string | null;
}

export type DocumentChannel = 'upload' | 'email' | 'scan' | 'api' | 'mobile';

export interface DocumentSummary {
  readonly id: string;
  readonly title: string;
  readonly documentType: string;
  readonly status: DocumentStatus;
  /** Where the voucher stands in the office queue (§8.1); null for documents not in the queue. */
  readonly belegStatus?: BelegStatusValue | null;
  /** Null means 'Ohne Mandant' - a visible state, never a guess (§5.2). */
  readonly mandantId?: string | null;
  readonly channel: DocumentChannel;
  readonly senderName?: string | null;
  /** What the platform read about the sender from the letter (address, telephone, e-mail, website, VAT id). */
  readonly sender?: DocumentSender | null;
  readonly senderContactId?: string | null;
  /** The person who uploaded the document; null for mail and imports. */
  readonly uploadedByName?: string | null;
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

/** The signals the platform can read about a voucher's client (§5.2). */
export type MandantSignalType =
  | 'ABSENDER_EMAIL'
  | 'IBAN'
  | 'USTID'
  | 'STEUERNUMMER'
  | 'FIRMENNAME'
  | 'SCHLUESSELWORT';

/** Whether a remembered rule came from a person's correction or from an automatic decision. */
export type MandantRuleSource = 'AUTO' | 'MANUELL';

/** One learned rule as `GET /settings/mandant-rules` serves it. */
export interface MandantRule {
  readonly id: string;
  readonly signalTyp: MandantSignalType;
  /** The normalised signal the matcher compares. */
  readonly muster: string;
  readonly mandantId: string | null;
  readonly mandantName: string | null;
  readonly quelle: MandantRuleSource;
  /** How often an automatic assignment used this rule. */
  readonly treffer: number;
  readonly letzteNutzung: IsoDateTime | null;
  readonly createdAt: IsoDateTime | null;
}

/** Everything `GET /settings/mandant-rules` answers. */
export interface MandantRuleList {
  readonly items: readonly MandantRule[];
  readonly count: number;
}

/**
 * Why a routing run did or did not assign a client (§5.2).
 *
 * `AMBIGUOUS` and `BELOW_THRESHOLD` are answers, not failures: the voucher stays "Ohne Mandant" and a
 * person decides. The screen must say which of the two it was.
 */
export type MandantRoutingStatus =
  | 'ASSIGNED'
  | 'ALREADY_ASSIGNED'
  | 'AMBIGUOUS'
  | 'BELOW_THRESHOLD'
  | 'NO_SIGNALS';

/** One signal the routing used, with the weight it carried. */
export interface MandantRoutingSignal {
  readonly type: MandantSignalType;
  readonly value: string;
  readonly strength: number;
}

/** One client that matched, with the confidence reached after combining its signals. */
export interface MandantRoutingCandidate {
  readonly mandantId: string;
  readonly mandantName: string | null;
  readonly confidence: number;
}

/** The answer of `POST /documents/{id}/mandant-reroute`. */
export interface MandantRoutingResult {
  readonly documentId: string;
  readonly status: MandantRoutingStatus;
  readonly mandantId: string | null;
  readonly mandantName: string | null;
  readonly confidence: number | null;
  readonly signals: readonly MandantRoutingSignal[];
  readonly candidates: readonly MandantRoutingCandidate[];
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

/**
 * What happened to an invoice that was sent to its customer.
 *
 * The status is the mail provider's answer and nothing more: "SENT" means the message was handed over, not
 * that it arrived. The wording in the interface must not promise delivery.
 */
export interface InvoiceSendResult {
  readonly invoiceId: string;
  readonly documentId: string;
  readonly messageId: string;
  readonly status: string;
  readonly providerMessageId: string;
  readonly description: string;
  readonly to: readonly string[];
  readonly subject: string;
  readonly documentFilename: string;
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

/**
 * Where a client stands (Kanzlei edition, §5.5): `RISK` means somebody is exposed (a voucher failed, a
 * deadline has passed), `ACTION` means work is waiting, `OK` means neither. The server decides; the screen
 * only paints it.
 */
export type MandantHealth = 'OK' | 'ACTION' | 'RISK';

/** One row of the client list: the counters the office reads without opening the client. */
export interface MandantSummary {
  readonly id: string;
  readonly displayName: string;
  readonly contactType: ContactType;
  readonly email: string | null;
  readonly city: string | null;
  readonly documentsTotal: number;
  /** Vouchers in `NEU` or `OFFEN` - still to be booked. */
  readonly offen: number;
  readonly zuPruefen: number;
  readonly exportiert: number;
  readonly fehler: number;
  /** Active deadlines whose due date is today or later; an overdue one moves the client to `RISK`. */
  readonly deadlinesOpen: number;
  readonly lastActivityAt: IsoDate | null;
  readonly health: MandantHealth;
}

/** A voucher on the client card. */
export interface MandantDocument {
  readonly id: string;
  readonly filename: string;
  readonly belegStatus: BelegStatusValue;
  readonly date: IsoDate;
}

/** A deadline that is still ahead. */
export interface MandantDeadline {
  readonly id: string;
  readonly deadlineType: string;
  readonly dueDate: IsoDate;
  readonly requiresVerification: boolean;
  readonly documentId: string | null;
}

/** The client card: the list row plus the newest vouchers and the open deadlines. */
export interface MandantDetail extends MandantSummary {
  readonly lastDocuments: readonly MandantDocument[];
  readonly openDeadlines: readonly MandantDeadline[];
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

/**
 * Whether this installation can offer an OAuth mailbox connection, and what the operator still has to do.
 *
 * <p>`configured` is false on a fresh installation: registering the application with Microsoft or Google is a
 * step only the operator can take, and the screen must say so instead of offering a button that cannot work.
 * `missingConfiguration` names the settings, not secrets — the client secret itself never reaches the browser.</p>
 */
export interface MailOAuthProviderSummary {
  readonly provider: string;
  readonly displayName: string;
  readonly configured: boolean;
  readonly missingConfiguration: readonly string[];
  readonly redirectUri: string;
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
  /**
   * The permissions the server granted this person (`settings.view`, `settings.manage_rules`, …). The
   * interface only follows them to hide what would be refused anyway; every call is checked again on the
   * server. Absent when the session predates this field — the screens then do not hide anything.
   */
  readonly permissions?: readonly string[];
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

/**
 * The date kinds a word can explain. `OTHER` exists in the classifier for a date that belongs to no
 * kind, but it is never a word's category — the settings endpoint refuses it.
 */
export type DateMarkerKind =
  | 'PAYMENT_DEADLINE'
  | 'OBJECTION_DEADLINE'
  | 'DEADLINE'
  | 'APPOINTMENT'
  | 'VALIDITY_END'
  | 'DOCUMENT_DATE';

/**
 * One word that explains a date as seen by a workspace: the platform defaults plus the workspace's own
 * words, its own word winning. `system` marks a platform default (read-only); `tenantOverride` marks an
 * own word that replaces a default with the same kind and phrase (a switched-off or relabelled default).
 */
export interface DateMarker {
  readonly id: string;
  readonly kind: DateMarkerKind;
  readonly phrase: string;
  readonly label: string | null;
  readonly enabled: boolean;
  readonly system: boolean;
  readonly tenantOverride: boolean;
}

/** Everything `GET /settings/date-markers` answers: the effective vocabulary and how many words it holds. */
export interface DateMarkerList {
  readonly items: readonly DateMarker[];
  readonly count: number;
}

/**
 * One row of the account mapping the DATEV export reads (Kanzlei edition, §5.6).
 *
 * <p>A row may name a client, a supplier, a category, or a combination — the export picks the most specific
 * matching row. The server writes only values that exist, so a cleared dimension arrives as `undefined`; the
 * screen normalises it to `null`. Without at least one row the export skips every voucher with
 * "Kein Konto-Mapping", which is why the screen says so.</p>
 */
export interface KontoMapping {
  readonly id: string;
  readonly mandantId: string | null;
  readonly mandantName: string | null;
  readonly supplier: string | null;
  readonly category: string | null;
  readonly sollkonto: string;
  readonly habenkonto: string;
  readonly steuerschluessel: string | null;
  readonly createdAt: IsoDateTime | null;
}

/** Everything `GET /settings/konto-mapping` answers: the rows and the categories the dialog may offer. */
export interface KontoMappingList {
  readonly items: readonly KontoMapping[];
  readonly count: number;
  readonly categories: readonly string[];
}

/**
 * The full state of one row, for create and for update. A patch replaces the row, so a field left out or set
 * to `null` clears that dimension; the two accounts are mandatory.
 */
export interface KontoMappingInput {
  readonly mandantId: string | null;
  readonly supplier: string | null;
  readonly category: string | null;
  readonly sollkonto: string;
  readonly habenkonto: string;
  readonly steuerschluessel: string | null;
}

/** One CSV line the import did not write, with the sentence the screen shows. */
export interface KontoMappingSkippedRow {
  /** 1-based physical line of the file; the header is line 1. */
  readonly line: number;
  readonly reason: string;
}

/** What a CSV import did: rows written, rows changed, and every line it left alone. */
export interface KontoMappingImportReport {
  readonly created: number;
  readonly updated: number;
  readonly skipped: readonly KontoMappingSkippedRow[];
}

/** The filters of the mapping list; every field is optional. */
export interface KontoMappingQuery {
  readonly mandantId?: string;
  readonly supplier?: string;
  readonly category?: string;
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

export interface TaskChecklistItem {
  readonly id: string;
  readonly label: string;
  readonly required: boolean;
  readonly done: boolean;
}

/** One task with what it takes to finish it: the criterion, the checklist and the document it came from. */
export interface TaskDetail {
  readonly id: string;
  readonly title: string;
  readonly description: string | null;
  readonly status: string;
  readonly finished: boolean;
  readonly dueAt: string | null;
  readonly completionCriteria: string | null;
  readonly requiresApproval: boolean;
  readonly approved: boolean;
  readonly sourceDocumentId: string | null;
  readonly caseId: string | null;
  readonly caseReference: string | null;
  readonly checklist: readonly TaskChecklistItem[];
}

/** A separate workspace of the signed-in person (one company, one client of an accountant). */
export interface WorkspaceEntry {
  readonly tenantId: string;
  readonly name: string;
  readonly accountType: string;
  readonly owner: boolean;
  readonly planCode: string | null;
  readonly current: boolean;
}

export interface WorkspaceList {
  readonly items: readonly WorkspaceEntry[];
  readonly capabilities: { readonly canCreateClients: boolean; readonly professionalPlan: boolean };
}

/** The own profile; the e-mail address is shown but changed elsewhere (it is the way in). */
export interface ProfileData {
  readonly email: string;
  readonly displayName: string;
  readonly locale: 'de' | 'en' | 'ru';
  readonly timeZone: string;
  readonly avatarVersion: number | null;
}

/** What the public legal pages need: the operator's entered data, what is still missing, and the version of the texts. */
export interface LegalInfo {
  readonly operator: Readonly<Record<string, string>>;
  readonly missing: readonly string[];
  readonly version: string;
  readonly trialDays: number;
  readonly retentionDays: number;
}

/** Uploaded pages that probably belong together, in the order the platform proposes. */
export interface MergeGroup {
  readonly documentIds: readonly string[];
  readonly filenames: readonly string[];
  readonly suggestedTitle: string;
}

/**
 * How many vouchers stand where in the office queue (§5.1).
 *
 * <p>Named after the bookkeeper's states, not the pipeline's: the screen shows these six numbers and nothing
 * else, because a counter nobody acts on is decoration.</p>
 */
/** The five states of a voucher in the office queue (§8.1). */
export type BelegStatusValue = 'NEU' | 'OFFEN' | 'ZU_PRUEFEN' | 'EXPORTIERT' | 'FEHLER';

export interface BelegCounts {
  readonly neu: number;
  readonly offen: number;
  readonly zuPruefen: number;
  readonly exportiert: number;
  readonly fehler: number;
  /**
   * Vouchers without a client. Null when the counter cannot be answered - the screen then says "not known"
   * instead of zero, because zero would claim every voucher has a client.
   */
  readonly ohneMandant: number | null;
}

/**
 * One voucher that was deliberately left out of a DATEV package, with the office's reason.
 *
 * <p>The reason comes from the server in words ("Ohne Mandant", "Kein Konto-Mapping") because it is the
 * sentence the package itself stores. The screen translates the two known reasons and shows anything else
 * unchanged, so a new reason never disappears.</p>
 */
export interface DatevSkippedDocument {
  readonly documentId: string;
  readonly reason: string;
}

/** What the creation of a DATEV package answered. */
export interface DatevExportResult {
  readonly id: string;
  readonly belegCount: number;
  readonly totalAmount: number;
  readonly filename: string;
  readonly skipped: readonly DatevSkippedDocument[];
}

/** One package in the export history. */
export interface DatevExportBatch {
  readonly id: string;
  readonly createdAt: IsoDateTime;
  readonly createdBy: string | null;
  readonly createdByName: string | null;
  readonly periodFrom: IsoDate;
  readonly periodTo: IsoDate;
  readonly mandantId: string | null;
  readonly mandantName: string | null;
  readonly belegCount: number;
  readonly totalAmount: number;
  readonly status: 'CREATED' | 'FAILED';
  readonly filename: string;
  readonly skippedCount: number;
}
