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
  'task' | 'deadline' | 'mail' | 'review' | 'approval' | 'integration' | 'automation' | 'archive';

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

export interface DashboardSummary {
  readonly generatedAt: IsoDateTime;
  readonly counts: Readonly<Record<string, number>>;
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
