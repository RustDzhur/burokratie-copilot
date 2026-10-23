/**
 * Endpoint catalogue.
 *
 * Every call the UI makes is declared here, grouped by the API groups from
 * docs/task.md §20. Components never build paths themselves, so a change in the
 * backend contract is a single-file change on the client.
 *
 * Mutating calls that create records or trigger processing pass
 * `idempotencyKey: true`, which makes the client generate a UUID v4 for the
 * `Idempotency-Key` header (§20: "идемпотентность, если требуется").
 */

import { ApiError, isApiError } from './errors';
import { normalizePage, type ApiClient, type ListParams, type Page, type UploadOptions } from './client';
import {
  approvalFromFields,
  documentDetailFromWire,
  historyFromWire,
  documentFromWire,
  reviewItemFromWire,
  wireSort,
  wireSourcesFor,
  wireStatusesFor,
  type AuditEventsWire,
  type DocumentWire,
  type FieldsWire,
  type ReviewDetailWire,
  type ReviewQueueEntryWire,
  type UploadWire,
} from './wire/documents';
import type { ProcessingFlow } from './processing';
import { approvalFromWire, workspaceFromWire } from './wire/autopilot';
import {
  CASE_SORT,
  INVOICE_SORT,
  TASK_SORT,
  caseDetailFromWire,
  caseFromWire,
  caseStatusesFor,
  dueInstant,
  prioritiesFor,
  priorityToWire,
  sortFor,
  taskFromWire,
  taskStatusesFor,
  taskStatusToWire,
  type CaseDetailWire,
  type CaseWire,
  type TaskWire,
} from './wire/work';
import {
  decimalFromInput,
  directionToWire,
  invoiceFromWire,
  invoiceStatusesFor,
  paymentFromWire,
  paymentStatusesFor,
  type BankTransactionWire,
  type InvoiceWire,
} from './wire/finance';
import {
  auditEntryFromWire,
  deviceFromWire,
  integrationFromWire,
  invitedMemberFromWire,
  memberFromWire,
  notificationFromWire,
  preferencesFromWire,
  preferencesToWire,
  roleToWire,
  type AuditEventWire,
  type DeviceWire,
  type IntegrationWire,
  type MemberWire,
  type NotificationWire,
  type PendingInvitationWire,
  type PreferenceWire,
} from './wire/people';
import {
  automationCode,
  automationFromWire,
  automationStartKind,
  automationStatusMatches,
  calendarEventFromWire,
  contactFromWire,
  contactKindToWire,
  mandantDetailFromWire,
  mandantFromWire,
  mailFolderToWire,
  mailFromWire,
  mailStatusToWire,
  placeholdersIn,
  templateCategoryToWire,
  templateCode,
  templateFromWire,
  type CalendarEventWire,
  type ContactWire,
  type MandantDetailWire,
  type MandantWire,
  type MailWire,
  type TemplateWire,
  type WorkflowWire,
} from './wire/misc';
import { normalizeGraph, type Graph, type PaletteEntry } from '../workflow/graph';
import { collectPages, csvBlob, toCsv } from './csv';
import type {
  AccessibilityPreferences,
  ArchiveEntry,
  AuditEntry,
  AutomationSummary,
  CalendarEvent,
  CaseSummary,
  ContactSummary,
  DatevExportBatch,
  DatevExportResult,
  DashboardSummary,
  DeviceSummary,
  DocumentDetail,
  DocumentHistoryEntry,
  BelegCounts,
  BelegBatchAction,
  BelegBatchResult,
  BelegStatusValue,
  DocumentSummary,
  AdminOverview,
  AdminService,
  AdminTenant,
  BillingInvoice,
  BillingState,
  InvoiceRequest,
  PlanSummary,
  SubscriptionInfo,
  IntegrationCatalogueEntry,
  IntegrationSummary,
  MailAccountSummary,
  MailCollectResult,
  MailOAuthProviderSummary,
  InvoiceSendResult,
  InvoiceSummary,
  MailMessageSummary,
  MemberRole,
  MemberSummary,
  NotificationPreferences,
  NotificationSummary,
  PaymentSummary,
  ReportResult,
  ReviewItem,
  SecuritySettings,
  SessionUser,
  TaskSummary,
  TemplateSummary,
  CaseDeadline,
  ApprovalDecision,
  ContactProfile,
  InvoiceMemory,
  RelationshipItem,
  TimelineEntry,
  ApprovalRequestItem,
  CaseWorkspace,
  CaseDetail,
  CatalogueEntry,
  DateMarker,
  DateMarkerKind,
  DateMarkerList,
  KontoMapping,
  KontoMappingImportReport,
  KontoMappingInput,
  KontoMappingList,
  KontoMappingQuery,
  MandantRule,
  MandantRuleList,
  MandantRuleSource,
  MandantRoutingCandidate,
  MandantRoutingResult,
  MandantRoutingSignal,
  MandantRoutingStatus,
  MandantDetail,
  MandantSummary,
  MandantSignalType,
  MandantRequest,
  MandantRequestDraftPayload,
  MandantRequestPreview,
  MandantRequestStatus,
  DeletionKind,
  MailMessageDetail,
  TelegramStatus,
  StoredExplanation,
  RuleSetting,
  RuleSettingVersion,
  DocumentCase,
  MergeGroup,
  LegalInfo,
  ProfileData,
  WorkspaceEntry,
  WorkspaceList,
  TaskDetail,
  ExplanationOutcome,
} from './types';

/**
 * A date found in a document, with the sentence it came from.
 *
 * <p>The sentence is not decoration: it is what makes such a date checkable against the letter. A date
 * without its source cannot be verified, and a wrong date in a calendar is worse than an empty one.</p>
 */
/**
 * What stands in the way of sending a generated letter.
 *
 * <p>Missing requisites, statements nobody stands behind, and the problems in words - the same three the
 * backend reports, so the screen can show a reason rather than a red dot.</p>
 */
/**
 * A generated letter, as the list shows it.
 *
 * <p>{@code hasFile} is what lets the screen offer a download only where there is one; a letter that was
 * recorded and never produced must not look downloadable.</p>
 */
export interface GeneratedDocumentSummary {
  readonly id: string;
  readonly title: string;
  readonly outputFormat: string;
  readonly status: string;
  readonly caseId: string | null;
  readonly generatedAt: string | null;
  readonly hasFile: boolean;
}

export interface GeneratedDocumentReadiness {
  readonly status: string;
  readonly readyToSend: boolean;
  readonly missing: readonly { readonly key: string; readonly label: string; readonly value: string | null }[];
  readonly unconfirmed: readonly { readonly text: string }[];
  readonly problems: readonly string[];
}

export interface DetectedDate {
  /** The date as it is written in the document, e.g. 31.10.2026. */
  readonly text: string;
  /** The same date normalised to JJJJ-MM-TT, or null when it could not be read unambiguously. */
  readonly date: string | null;
  readonly page: number | null;
  /** The sentence around it, so that the offer can be checked. */
  readonly snippet: string | null;
  /** FIELD when it came from an extracted field, PAGE_TEXT when it was read from the page. */
  readonly origin: string;
  /** What the wording says it is: PAYMENT_DEADLINE, APPOINTMENT, DOCUMENT_DATE, OTHER and so on. */
  readonly kind: string;
  readonly confidence: number;
  /** The words that decided the kind. */
  readonly evidence: string | null;
}

export interface LoginPayload {
  readonly email: string;
  readonly password: string;
  readonly rememberMe?: boolean;
}

/** What `GET /auth/me` answers. Mirrors `AuthController.MeResponse`; the screens never see this shape. */
interface MeResponse {
  readonly userId: string;
  readonly tenantId: string | null;
  readonly email: string;
  readonly displayName: string | null;
  readonly workspaceName: string | null;
  readonly roles: readonly string[];
  readonly onboardingCompleted?: boolean;
  readonly mfaEnrolmentRequired?: boolean;
  readonly permissions?: readonly string[];
  readonly subscription?: SubscriptionWire | null;
  readonly avatarVersion?: number | null;
}

interface SubscriptionWire {
  readonly plan_code: string;
  readonly plan_name: string;
  readonly tier?: number;
  readonly analyses_per_month?: number | null;
  readonly status: SubscriptionInfo['status'];
  readonly access_until: string;
  readonly retention_until?: string | null;
  readonly days_left: number;
  readonly blocked: boolean;
  readonly data_choice?: 'KEEP' | 'DELETE' | null;
  readonly retention_days: number;
}

function subscriptionFromWire(wire: SubscriptionWire): SubscriptionInfo {
  return {
    planCode: wire.plan_code,
    planName: wire.plan_name,
    planTier: wire.tier,
    analysesPerMonth: wire.analyses_per_month ?? null,
    status: wire.status,
    accessUntil: wire.access_until,
    retentionUntil: wire.retention_until ?? null,
    daysLeft: wire.days_left,
    blocked: wire.blocked,
    dataChoice: wire.data_choice ?? null,
    retentionDays: wire.retention_days,
  };
}

const ROLE_FROM_BACKEND: Readonly<Record<string, MemberRole>> = {
  OWNER: 'owner',
  ADMIN: 'admin',
  MANAGER: 'admin',
  EMPLOYEE: 'member',
  READ_ONLY: 'member',
  MEMBER: 'member',
  ACCOUNTANT: 'accountant',
  AUDITOR: 'auditor',
};

/** The one place that turns the backend's principal description into the user the screens work with. */
export function sessionUserFromMe(me: MeResponse): SessionUser {
  return {
    id: me.userId,
    name: me.displayName ?? me.email,
    email: me.email,
    roles: me.roles.flatMap((code) => {
      const role = ROLE_FROM_BACKEND[code];
      return role ? [role] : [];
    }),
    workspaceId: me.tenantId ?? '',
    workspaceName: me.workspaceName ?? '',
    avatarUrl: me.avatarVersion ? `/api/v1/profile/avatar?v=${me.avatarVersion}` : null,
    onboardingCompleted: me.onboardingCompleted ?? true,
    mfaEnrolmentRequired: me.mfaEnrolmentRequired ?? false,
    platformAdmin: (me.permissions ?? []).includes('platform.admin'),
    permissions: me.permissions ?? [],
    subscription: me.subscription ? subscriptionFromWire(me.subscription) : null,
  };
}

/** One date word exactly as `GET /settings/date-markers` serves it. */
interface DateMarkerWire {
  readonly id: string;
  readonly kind: DateMarkerKind;
  readonly phrase: string;
  readonly label?: string | null;
  readonly enabled?: boolean;
  readonly system?: boolean;
  readonly tenantOverride?: boolean;
}

interface DateMarkerListWire {
  readonly items?: readonly DateMarkerWire[];
  readonly count?: number;
}

/** Readable defaults for a field an older backend may leave out: a word is active unless it says otherwise. */
function dateMarkerFromWire(wire: DateMarkerWire): DateMarker {
  return {
    id: wire.id,
    kind: wire.kind,
    phrase: wire.phrase,
    label: wire.label ?? null,
    enabled: wire.enabled !== false,
    system: wire.system === true,
    tenantOverride: wire.tenantOverride === true,
  };
}

/** What a workspace sends to add a word, or to switch a default off with the same kind and phrase. */
export interface DateMarkerInput {
  readonly kind: DateMarkerKind;
  readonly phrase: string;
  readonly label?: string;
  readonly enabled?: boolean;
}

export interface DateMarkerPatch {
  readonly enabled?: boolean;
  readonly phrase?: string;
  readonly label?: string;
}

/**
 * One account mapping exactly as `GET /settings/konto-mapping` serves it.
 *
 * <p>The API writes only values that exist, so a cleared dimension is absent rather than `null`; the
 * converter normalises that. The accounts and the id are the only values every backend version has.</p>
 */
interface KontoMappingWire {
  readonly id: string;
  readonly mandantId?: string | null;
  readonly mandantName?: string | null;
  readonly supplier?: string | null;
  readonly category?: string | null;
  readonly sollkonto: string;
  readonly habenkonto: string;
  readonly steuerschluessel?: string | null;
  readonly createdAt?: string | null;
}

interface KontoMappingListWire {
  readonly items?: readonly KontoMappingWire[];
  readonly count?: number;
  /** The categories of the document taxonomy, so the dialog does not repeat the list. */
  readonly categories?: readonly string[];
}

interface KontoMappingImportWire {
  readonly created?: number;
  readonly updated?: number;
  readonly skipped?: readonly { readonly line?: number; readonly reason?: string }[];
}

function kontoMappingFromWire(wire: KontoMappingWire): KontoMapping {
  return {
    id: wire.id,
    mandantId: wire.mandantId ?? null,
    mandantName: wire.mandantName ?? null,
    supplier: wire.supplier ?? null,
    category: wire.category ?? null,
    sollkonto: wire.sollkonto,
    habenkonto: wire.habenkonto,
    steuerschluessel: wire.steuerschluessel ?? null,
    createdAt: wire.createdAt ?? null,
  };
}

/**
 * One learned routing rule as the backend serves it.
 *
 * <p>A never-used rule has no {@code letzteNutzung} at all - Spring writes only values that exist - so the
 * converter normalises an absent field to {@code null} and the screen renders "never used" instead of a
 * date that is not there.</p>
 */
interface MandantRuleWire {
  readonly id: string;
  readonly signalTyp?: string;
  readonly muster?: string;
  readonly mandantId?: string | null;
  readonly mandantName?: string | null;
  readonly quelle?: string;
  readonly treffer?: number;
  readonly letzteNutzung?: string | null;
  readonly createdAt?: string | null;
}

interface MandantRuleListWire {
  readonly items?: readonly MandantRuleWire[];
  readonly count?: number;
}

function mandantRuleFromWire(wire: MandantRuleWire): MandantRule {
  return {
    id: wire.id,
    signalTyp: wire.signalTyp as MandantSignalType,
    muster: wire.muster ?? '',
    mandantId: wire.mandantId ?? null,
    mandantName: wire.mandantName ?? null,
    quelle: (wire.quelle === 'AUTO' ? 'AUTO' : 'MANUELL') satisfies MandantRuleSource,
    treffer: wire.treffer ?? 0,
    letzteNutzung: wire.letzteNutzung ?? null,
    createdAt: wire.createdAt ?? null,
  };
}

/** The answer of a routing run; every absent optional field means "nothing to show", not "zero". */
interface MandantRoutingWire {
  readonly documentId?: string;
  readonly status?: string;
  readonly mandantId?: string | null;
  readonly mandantName?: string | null;
  readonly confidence?: number | null;
  readonly signals?: readonly { readonly type?: string; readonly value?: string; readonly strength?: number }[];
  readonly candidates?: readonly {
    readonly mandantId?: string;
    readonly mandantName?: string | null;
    readonly confidence?: number;
  }[];
}

function mandantRoutingFromWire(wire: MandantRoutingWire): MandantRoutingResult {
  return {
    documentId: wire.documentId ?? '',
    status: (wire.status ?? 'NO_SIGNALS') as MandantRoutingStatus,
    mandantId: wire.mandantId ?? null,
    mandantName: wire.mandantName ?? null,
    confidence: wire.confidence ?? null,
    signals: (wire.signals ?? []).map((signal) => ({
      type: signal.type as MandantSignalType,
      value: signal.value ?? '',
      strength: signal.strength ?? 0,
    })) satisfies readonly MandantRoutingSignal[],
    candidates: (wire.candidates ?? []).map((candidate) => ({
      mandantId: candidate.mandantId ?? '',
      mandantName: candidate.mandantName ?? null,
      confidence: candidate.confidence ?? 0,
    })) satisfies readonly MandantRoutingCandidate[],
  };
}

/**
 * The categories the mapping dialog offers when the backend does not send its own.
 *
 * <p>The taxonomy's values are in migration V3 (`document_types_category_chk`); this copy exists only so an
 * older server still gets a usable dialog. The server's list always wins.</p>
 */
const FALLBACK_KONTO_CATEGORIES: readonly string[] = [
  'FINANCE',
  'CONTRACTS',
  'GOVERNMENT',
  'HOUSING',
  'EMPLOYMENT',
  'FAMILY_EDUCATION_HEALTH',
  'LEGAL',
  'OTHER',
];

export interface RegisterPayload {
  /** The person ticked the box for the terms and the privacy notice; the server records which version. */
  readonly acceptedTerms: boolean;
  readonly fullName: string;
  readonly email: string;
  readonly password: string;
}

export interface OnboardingPayload {
  readonly workspaceName: string;
  readonly sources: readonly string[];
  readonly notifications: readonly string[];
}

export interface ConfirmFieldsPayload {
  /** The type the person chose or took over; sent as the reviewer's decision, never implied. */
  readonly typeCode?: string | null;
  readonly fields: readonly { readonly name: string; readonly value: string }[];
}

export interface CreateTaskPayload {
  readonly title: string;
  readonly dueDate?: string;
  readonly priority: TaskSummary['priority'];
  readonly assigneeId?: string;
  readonly caseId?: string;
  /** What has to be true for the task to count as done; the backend refuses to finish a task without one. */
  readonly completionCriteria?: string;
}

/**
 * The person's own menu: the order of the main list and the screens they put away into "Weitere".
 *
 * <p>Empty arrays mean the standard menu by sections. An account that never changed anything therefore
 * keeps the layout it always had — a preference only exists once somebody actually reordered or hid
 * something.</p>
 */
export interface NavigationPreferences {
  readonly order: readonly string[];
  readonly hidden: readonly string[];
  /** When the menu was last changed; `null` while the standard menu applies. */
  readonly updatedAt: string | null;
}

/** What `PUT /me/navigation` sends: the complete menu, never a patch. */
export interface NavigationPreferencesInput {
  readonly order: readonly string[];
  readonly hidden: readonly string[];
}

export interface ApiResources {
  readonly auth: {
    /** Resolves to the user, or to `mfaRequired` when a second factor has to be proven first. */
    login: (payload: LoginPayload) => Promise<SessionUser | { readonly mfaRequired: true }>;
    verifyMfa: (code: string) => Promise<SessionUser>;
    /** Whether sign-in through an external identity provider is set up. */
    ssoAvailable: () => Promise<boolean>;
    logout: () => Promise<void>;
    register: (payload: RegisterPayload) => Promise<{ userId: string; email: string }>;
    verifyEmail: (payload: { token: string }) => Promise<void>;
    resendVerification: (payload: { email: string }) => Promise<void>;
    requestPasswordReset: (payload: { email: string }) => Promise<void>;
    confirmPasswordReset: (payload: { token: string; password: string }) => Promise<void>;
    session: (signal?: AbortSignal) => Promise<SessionUser>;
    completeOnboarding: (payload: OnboardingPayload) => Promise<SessionUser>;
  };
  readonly dashboard: {
    summary: (signal?: AbortSignal) => Promise<DashboardSummary>;
  };
  readonly documents: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<DocumentSummary>>;
    /** The six numbers above the Posteingang (§5.1); one request, so the screen cannot show two truths. */
    belegCounts: (signal?: AbortSignal) => Promise<BelegCounts>;
    /** Moves a voucher through the office queue (§8.1); the server refuses a transition that is a lie. */
    setBelegStatus: (id: string, status: BelegStatusValue) => Promise<void>;
    /**
     * Applies one action to a selection of vouchers (§5.1). The answer is honest about both halves: how many
     * changed and which ones the status machine refused, with the reason the office has to see.
     */
    batch: (ids: readonly string[], action: BelegBatchAction, mandantId?: string | null) => Promise<BelegBatchResult>;
    export: (params?: ListParams) => Promise<Blob>;
    get: (id: string, signal?: AbortSignal) => Promise<DocumentDetail>;
    history: (id: string, signal?: AbortSignal) => Promise<Page<DocumentHistoryEntry>>;
    upload: (file: File, options?: UploadOptions) => Promise<DocumentSummary>;
    reprocess: (id: string) => Promise<void>;
    remove: (id: string) => Promise<void>;
    removeMany: (ids: readonly string[]) => Promise<{ deleted: number; refused: string[] }>;
    pipeline: (id: string, signal?: AbortSignal) => Promise<ProcessingFlow>;
    categoryCounts: (signal?: AbortSignal) => Promise<Readonly<Record<string, number>>>;
    catalogue: (signal?: AbortSignal) => Promise<readonly CatalogueEntry[]>;
    /** The client of a voucher (§5.2); null removes the assignment. */
    assignMandant: (id: string, mandantId: string | null) => Promise<void>;
    /**
     * Recomputes the client from the signals the voucher carries (§5.2). The same calculation the processing
     * pipeline runs; an existing assignment is never overwritten.
     */
    rerouteMandant: (id: string) => Promise<MandantRoutingResult>;
    /** The cases the document is linked to, as far as the reader may see them. */
    cases: (id: string, signal?: AbortSignal) => Promise<readonly DocumentCase[]>;
    createTask: (id: string) => Promise<void>;
    /** Groups of uploaded photos that probably are the pages of one document. */
    mergeCandidates: (signal?: AbortSignal) => Promise<readonly MergeGroup[]>;
    /** Makes one PDF of the pages in the given order and deletes the single ones. */
    merge: (documentIds: readonly string[], title?: string) => Promise<{ readonly documentId: string; readonly filename: string }>;
    /** A person files the document under a type they chose; re-sorting by the rules leaves it alone. */
    setType: (id: string, typeCode: string) => Promise<void>;
    /** Files the stored documents again by the current rules; returns what was done in numbers. */
    resort: () => Promise<Readonly<Record<'finished' | 'sendersFound' | 'checked' | 'unchanged' | 'moved' | 'sentToReview' | 'keptByPerson' | 'withoutText' | 'failed', number>>>;
    /** Reads the pages with AI vision (handwriting); the values come back as proposals. */
    aiRead: (id: string) => Promise<{ readonly status: string; readonly found: Readonly<Record<string, string>>; readonly summary: string | null }>;
    /** Dates found in the document, each with the page and the sentence it appears in. */
    detectedDates: (id: string, signal?: AbortSignal) => Promise<{ readonly dates: readonly DetectedDate[]; readonly count: number; readonly note: string }>;
    /** The stored explanation or translation; null when none was made yet. */
    explanation: (id: string, language: string, signal?: AbortSignal) => Promise<StoredExplanation | null>;
    explain: (id: string, language: string) => Promise<ExplanationOutcome>;
    translation: (id: string, language: string, simple: boolean, signal?: AbortSignal) => Promise<StoredExplanation | null>;
    translate: (id: string, language: string, simple: boolean) => Promise<ExplanationOutcome>;
    downloadOriginal: (id: string) => Promise<Blob>;
    confirmFields: (id: string, payload: ConfirmFieldsPayload) => Promise<void>;
    archive: (id: string) => Promise<void>;
  };
  readonly review: {
    queue: (params?: ListParams, signal?: AbortSignal) => Promise<Page<ReviewItem>>;
    confirm: (documentId: string, payload: ConfirmFieldsPayload) => Promise<void>;
    reject: (documentId: string, reason?: string) => Promise<void>;
  };
  readonly cases: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<CaseSummary>>;
    get: (id: string, signal?: AbortSignal) => Promise<CaseSummary>;
    detail: (id: string, signal?: AbortSignal) => Promise<CaseDetail>;
    deadlines: (id: string, signal?: AbortSignal) => Promise<readonly CaseDeadline[]>;
    /** Goal, completion criteria and plan of the case. */
    workspace: (id: string, signal?: AbortSignal) => Promise<CaseWorkspace>;
    setGoal: (id: string, goal: string, nextAction: string) => Promise<void>;
    addCriterion: (id: string, payload: { readonly kind: string; readonly description: string }) => Promise<void>;
    meetCriterion: (id: string, criterionId: string, note: string) => Promise<void>;
    waiveCriterion: (id: string, criterionId: string, reason: string) => Promise<void>;
    create: (payload: {
      readonly title: string;
      readonly caseType?: string;
    }) => Promise<CaseSummary>;
  };
  readonly tasks: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<TaskSummary>>;
    create: (payload: CreateTaskPayload) => Promise<TaskSummary>;
    setStatus: (id: string, status: TaskSummary['status']) => Promise<TaskSummary>;
    detail: (id: string, signal?: AbortSignal) => Promise<TaskDetail>;
    tick: (itemId: string, done: boolean) => Promise<void>;
    /** Finishes the task; `acknowledgeOpenItems` with a note finishes it although required items are open. */
    complete: (id: string, options?: { readonly note?: string; readonly acknowledgeOpenItems?: boolean }) => Promise<void>;
  };
  readonly calendar: {
    events: (params?: ListParams, signal?: AbortSignal) => Promise<Page<CalendarEvent>>;
    create: (payload: {
      readonly title: string;
      readonly date: string;
      readonly type: CalendarEvent['type'];
    }) => Promise<CalendarEvent>;
    /** Private addresses a phone's calendar app subscribes to. The secret comes back once, at creation. */
    readonly feeds: {
      list: (signal?: AbortSignal) => Promise<
        readonly { readonly id: string; readonly label: string; readonly locale: string; readonly lastUsedAt: string | null }[]
      >;
      create: (label: string, locale: string) => Promise<{ readonly id: string; readonly label: string; readonly token: string }>;
      revoke: (id: string) => Promise<void>;
    };
  };
  readonly invoices: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<InvoiceSummary>>;
    approve: (id: string) => Promise<InvoiceSummary>;
    create: (payload: {
      readonly number: string;
      readonly counterparty: string;
      readonly amount: string;
      readonly dueDate?: string;
      readonly direction: InvoiceSummary['direction'];
    }) => Promise<InvoiceSummary>;
    /**
     * The invoice as a PDF, as an address a link can point at.
     *
     * <p>A URL rather than a fetch, because the browser already knows how to download a file: the document is
     * served with its own filename and the tab stays where it was.</p>
     */
    pdfUrl: (id: string) => string;
    /** The invoice as an XRechnung (UBL), for a receiver that wants one. */
    xrechnungUrl: (id: string) => string;
    /**
     * Sends the invoice to the customer with the PDF attached.
     *
     * <p>The backend archives the PDF as a document, writes the letter and hands it to the mail provider.
     * The answer says what the provider did — accepted the message — and deliberately does not claim that
     * the customer received it.</p>
     */
    send: (
      id: string,
      payload?: { readonly to?: string; readonly subject?: string; readonly body?: string },
    ) => Promise<InvoiceSendResult>;
  };
  readonly payments: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<PaymentSummary>>;
    match: (id: string, invoiceId: string) => Promise<void>;
  };
  readonly ruleSettings: {
    list: (signal?: AbortSignal) => Promise<readonly RuleSetting[]>;
    change: (key: string, value: unknown, reason: string) => Promise<void>;
    reset: (key: string, reason: string) => Promise<void>;
    history: (key: string, signal?: AbortSignal) => Promise<readonly RuleSettingVersion[]>;
  };
  readonly memory: {
    profile: (contactId: string, signal?: AbortSignal) => Promise<ContactProfile>;
    contactTimeline: (contactId: string, signal?: AbortSignal) => Promise<readonly TimelineEntry[]>;
    contractTimeline: (contractId: string, signal?: AbortSignal) => Promise<readonly TimelineEntry[]>;
    contractRelationships: (contractId: string, signal?: AbortSignal) => Promise<readonly RelationshipItem[]>;
    documentRelationships: (documentId: string, signal?: AbortSignal) => Promise<readonly RelationshipItem[]>;
    invoice: (invoiceId: string, signal?: AbortSignal) => Promise<InvoiceMemory>;
    confirmRelationship: (id: string) => Promise<void>;
    rejectRelationship: (id: string) => Promise<void>;
    addIdentifier: (contactId: string, kind: string, value: string) => Promise<void>;
    removeIdentifier: (contactId: string, identifierId: string) => Promise<void>;
  };
  readonly contacts: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<ContactSummary>>;
    create: (payload: {
      readonly name: string;
      readonly contactType: ContactSummary['contactType'];
      readonly email?: string;
    }) => Promise<ContactSummary>;
  };
  /**
   * The clients of the Kanzlei and their state (§5.5).
   *
   * <p>Same records as `/contacts`, seen through the office's vocabulary: every row carries the counters of
   * the client's vouchers, its open deadlines and the health the server derived from them. The screen must not
   * recompute that - a second aggregation would be a second truth.</p>
   */
  readonly mandanten: {
    list: (
      params?: {
        readonly q?: string;
        /** Keeps only the clients whose health is not `OK`. */
        readonly onlyProblems?: boolean;
      },
      signal?: AbortSignal,
    ) => Promise<readonly MandantSummary[]>;
    get: (id: string, signal?: AbortSignal) => Promise<MandantDetail>;
  };
  /**
   * Requests to a client for missing vouchers (§5.7).
   *
   * <p>The text is generated by the server from the client and the voucher. The dialog shows it, a person
   * edits it, and the same text is stored; the client never builds the German letter itself, because then
   * the history and what left the house could disagree.</p>
   */
  readonly mandantRequests: {
    list: (
      params?: {
        readonly mandantId?: string;
        readonly documentId?: string;
        readonly status?: MandantRequestStatus;
      },
      signal?: AbortSignal,
    ) => Promise<readonly MandantRequest[]>;
    get: (id: string, signal?: AbortSignal) => Promise<MandantRequest>;
    /** The prefilled letter, not stored. */
    preview: (payload: MandantRequestDraftPayload) => Promise<MandantRequestPreview>;
    create: (payload: MandantRequestDraftPayload) => Promise<MandantRequest>;
    update: (
      id: string,
      payload: { readonly subject?: string; readonly body?: string; readonly note?: string | null },
    ) => Promise<MandantRequest>;
    /** Hands the letter over; a failure leaves the request a draft. */
    send: (id: string) => Promise<MandantRequest>;
    cancel: (id: string) => Promise<MandantRequest>;
    markAnswered: (id: string) => Promise<MandantRequest>;
  };
  /**
   * DATEV export packages (§5.6).
   *
   * <p>The CSV profile is provisional and not agreed with a Kanzlei. The client never renders the file as the
   * final DATEV format and the screen carries a permanent warning; only the download hands the bytes over.</p>
   */
  readonly exports: {
    readonly datev: {
      /** The packages of this account, newest first. */
      list: (signal?: AbortSignal) => Promise<readonly DatevExportBatch[]>;
      /** Creates a package; the answer names what went in and what was skipped, with reasons. */
      create: (payload: {
        readonly periodFrom: string;
        readonly periodTo: string;
        readonly mandantId: string | null;
      }) => Promise<DatevExportResult>;
      /** The CSV bytes; the caller downloads them under the package's own filename. */
      download: (id: string) => Promise<Blob>;
    };
  };
  readonly mail: {
    get: (id: string, signal?: AbortSignal) => Promise<MailMessageDetail>;
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<MailMessageSummary>>;
  };
  readonly generatedDocuments: {
    /**
     * Whether a generated letter is ready to be sent, and what stands in the way.
     *
     * <p>The backend could answer this and the client had no way to ask: the check existed, a route existed,
     * and a screen did not. This is the first half of closing that.</p>
     */
    /** The generated letters of this account, newest first. */
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<GeneratedDocumentSummary>>;
    readiness: (id: string, signal?: AbortSignal) => Promise<GeneratedDocumentReadiness>;
    /** The produced file, as an address a link can point at. */
    downloadUrl: (id: string) => string;
  };
  readonly templates: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<TemplateSummary>>;
    create: (payload: {
      readonly name: string;
      readonly templateType: string;
      readonly body?: string;
    }) => Promise<TemplateSummary>;
  };
  readonly guide: {
    ask: (payload: {
      readonly messages: readonly { readonly role: 'user' | 'assistant'; readonly text: string }[];
      readonly page: string;
      readonly locale: string;
    }) => Promise<{
      readonly available: boolean;
      readonly text: string;
      readonly open: string;
      readonly handoff: boolean;
      readonly reason: string;
    }>;
  };
  readonly automations: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<AutomationSummary>>;
    create: (payload: {
      readonly name: string;
      readonly trigger: string;
    }) => Promise<AutomationSummary>;
  };
  readonly integrations: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<IntegrationSummary>>;
    disconnect: (id: string) => Promise<void>;
    catalogue: (signal?: AbortSignal) => Promise<readonly IntegrationCatalogueEntry[]>;
  };
  readonly mailAccounts: {
    list: (signal?: AbortSignal) => Promise<readonly MailAccountSummary[]>;
    connect: (payload: {
      readonly displayName: string;
      readonly emailAddress: string;
      readonly password: string;
      readonly imapHost: string;
      readonly imapPort: number;
    }) => Promise<{ readonly accountId: string }>;
    collect: (accountId: string) => Promise<MailCollectResult>;
  };
  /**
   * OAuth mailbox connections. `startPath` is a URL the browser navigates to, not a fetch: the backend
   * answers with a redirect to the provider. Everything secret stays on the server.
   */
  readonly mailOAuth: {
    providers: (signal?: AbortSignal) => Promise<readonly MailOAuthProviderSummary[]>;
    startPath: (provider: string) => string;
  };
  readonly publicPlans: {
    get: (signal?: AbortSignal) => Promise<{
      readonly trialDays: number;
      readonly retentionDays: number;
      readonly plans: readonly PlanSummary[];
    }>;
  };
  readonly billing: {
    state: (signal?: AbortSignal) => Promise<BillingState>;
    changePlan: (planCode: string) => Promise<BillingState>;
    invoices: (signal?: AbortSignal) => Promise<readonly BillingInvoice[]>;
    createInvoice: (payload: InvoiceRequest) => Promise<BillingInvoice>;
    invoicePdfUrl: (id: string) => string;
    exportUrl: () => string;
    keepData: () => Promise<void>;
    deleteData: () => Promise<void>;
  };
  readonly admin: {
    overview: (signal?: AbortSignal) => Promise<AdminOverview>;
    tenants: (params: { q?: string; status?: string }, signal?: AbortSignal) => Promise<readonly AdminTenant[]>;
    extend: (id: string, months: number) => Promise<void>;
    grantDays: (id: string, days: number) => Promise<void>;
    setSuspended: (id: string, suspended: boolean) => Promise<void>;
    setPlan: (id: string, planCode: string) => Promise<void>;
    deleteTenant: (id: string, confirmName: string) => Promise<void>;
    plans: (signal?: AbortSignal) => Promise<readonly PlanSummary[]>;
    editPlan: (code: string, payload: { name?: string; priceCents?: number; active?: boolean }) => Promise<void>;
    settings: (signal?: AbortSignal) => Promise<Record<string, unknown>>;
    putSetting: (key: string, value: unknown) => Promise<Record<string, unknown>>;
    invoices: (status?: string, signal?: AbortSignal) => Promise<readonly BillingInvoice[]>;
    markPaid: (id: string) => Promise<void>;
    cancelInvoice: (id: string) => Promise<void>;
    invoicePdfUrl: (id: string) => string;
    runLifecycle: () => Promise<void>;
    services: (signal?: AbortSignal) => Promise<readonly AdminService[]>;
  };
  readonly devices: {
    list: (signal?: AbortSignal) => Promise<Page<DeviceSummary>>;
    scan: (id: string, profile?: string) => Promise<void>;
  };
  readonly telegram: {
    status: (signal?: AbortSignal) => Promise<TelegramStatus>;
    createLinkCode: () => Promise<TelegramStatus>;
    setPreferences: (payload: { level?: TelegramStatus['level']; enabled?: boolean }) => Promise<void>;
    test: () => Promise<void>;
    unlink: () => Promise<void>;
  };
  readonly approvals: {
    center: (signal?: AbortSignal) => Promise<readonly ApprovalRequestItem[]>;
    decide: (id: string, decision: ApprovalDecision) => Promise<void>;
  };
  readonly demo: {
    /** Opens a demo cabinet for the chosen purpose; the browser is signed in to it. */
    start: (persona: string) => Promise<{ readonly persona: string; readonly next: string }>;
    status: (signal?: AbortSignal) => Promise<{ readonly demo: boolean; readonly persona: string; readonly expiresAt?: string }>;
    /** Leaves the demo: everything that was created in it is deleted. */
    exit: () => Promise<void>;
  };
  readonly legal: {
    get: (signal?: AbortSignal) => Promise<LegalInfo>;
  };
  readonly profile: {
    get: (signal?: AbortSignal) => Promise<ProfileData>;
    update: (values: { readonly displayName: string; readonly locale: string; readonly timeZone: string }) => Promise<ProfileData>;
    uploadAvatar: (file: Blob) => Promise<void>;
    removeAvatar: () => Promise<void>;
  };
  readonly workspaces: {
    list: (signal?: AbortSignal) => Promise<WorkspaceList>;
    switchTo: (tenantId: string) => Promise<void>;
    createClient: (name: string) => Promise<WorkspaceEntry>;
  };
  readonly deletion: {
    remove: (kind: DeletionKind, ids: readonly string[]) => Promise<{ deleted: number; refused: string[] }>;
  };
  readonly members: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<MemberSummary>>;
    invite: (payload: {
      readonly email: string;
      readonly role: MemberSummary['role'];
    }) => Promise<void>;
    setRole: (id: string, role: MemberSummary['role']) => Promise<MemberSummary>;
  };
  readonly reports: {
    get: (type: string, period: string, signal?: AbortSignal) => Promise<ReportResult>;
    export: (type: string, period: string) => Promise<Blob>;
  };
  readonly archive: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<ArchiveEntry>>;
    export: (params?: ListParams) => Promise<Blob>;
  };
  readonly notifications: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<NotificationSummary>>;
    markRead: (id: string) => Promise<void>;
    unreadCount: (signal?: AbortSignal) => Promise<number>;
    markAllRead: () => Promise<void>;
    preferences: (signal?: AbortSignal) => Promise<NotificationPreferences>;
    savePreferences: (preferences: NotificationPreferences) => Promise<NotificationPreferences>;
  };
  readonly audit: {
    list: (params?: ListParams, signal?: AbortSignal) => Promise<Page<AuditEntry>>;
    export: (params?: ListParams) => Promise<Blob>;
  };
  readonly workflows: {
    palette: (signal?: AbortSignal) => Promise<readonly PaletteEntry[]>;
    /** The saved graph of a process; `null` when no version has been published yet. */
    graph: (id: string, signal?: AbortSignal) => Promise<Graph | null>;
    check: (id: string, graph: Graph) => Promise<readonly { readonly code: string; readonly message: string }[]>;
    publish: (id: string, graph: Graph, note?: string) => Promise<{ version: number }>;
  };
  readonly mfa: {
    /** Starts an enrolment: the secret to put into an authenticator app, not yet active. */
    begin: () => Promise<{ methodId: string; secret: string; provisioningUri: string }>;
    /** Confirms the first code; the recovery codes are shown once. */
    confirm: (methodId: string, code: string) => Promise<{ recoveryCodes: readonly string[] }>;
    /** Switches the caller's active factor off. */
    disable: () => Promise<void>;
  };
  readonly navigation: {
    /** The person's own menu; empty arrays while nothing was saved. */
    get: (signal?: AbortSignal) => Promise<NavigationPreferences>;
    /** Replaces the whole menu; the request carries the complete order, so repeating it is harmless. */
    save: (preferences: NavigationPreferencesInput) => Promise<NavigationPreferences>;
    /** Returns to the standard menu by sections. */
    reset: () => Promise<void>;
  };
  readonly settings: {
    security: (signal?: AbortSignal) => Promise<SecuritySettings>;
    revokeSession: (id: string) => Promise<void>;
    changePassword: (payload: {
      readonly currentPassword: string;
      readonly newPassword: string;
    }) => Promise<void>;
    accessibility: (signal?: AbortSignal) => Promise<AccessibilityPreferences>;
    saveAccessibility: (preferences: AccessibilityPreferences) => Promise<AccessibilityPreferences>;
    /**
     * The words that explain a date (§9.3): the platform defaults, the workspace's own words and the
     * defaults it switched off. `create` also switches a default off — a default cannot be patched (409),
     * only replaced by an own word with the same kind and phrase.
     */
    readonly dateMarkers: {
      list: (signal?: AbortSignal) => Promise<DateMarkerList>;
      create: (payload: DateMarkerInput) => Promise<DateMarker>;
      update: (id: string, patch: DateMarkerPatch) => Promise<DateMarker>;
      remove: (id: string) => Promise<void>;
      reset: () => Promise<{ readonly deleted: number }>;
    };
    /**
     * The account mapping of the DATEV export (§5.6). Without a row here the export skips every voucher with
     * "Kein Konto-Mapping", so this is the screen that makes the export produce lines at all.
     */
    readonly kontoMapping: {
      list: (query?: KontoMappingQuery, signal?: AbortSignal) => Promise<KontoMappingList>;
      create: (payload: KontoMappingInput) => Promise<KontoMapping>;
      update: (id: string, payload: KontoMappingInput) => Promise<KontoMapping>;
      remove: (id: string) => Promise<void>;
      /** The CSV the Kanzlei exchanges; the report names every line the import did not write. */
      importCsv: (file: File) => Promise<KontoMappingImportReport>;
    };
    /**
     * The rules the platform learned for assigning vouchers to clients (§5.2). They are the reason a
     * voucher landed in a client's bookkeeping without a click, so they must be visible and removable.
     */
    readonly mandantRules: {
      list: (signal?: AbortSignal) => Promise<MandantRuleList>;
      remove: (id: string) => Promise<void>;
    };
  };
}

/**
 * Decides the open review of a document. The screens talk about documents; the backend decides reviews,
 * so the open one is looked up first. A document with no open review has nothing to decide, which is
 * reported rather than silently ignored.
 */
async function decideOpenReview(
  client: ApiClient,
  documentId: string,
  decision: { readonly decision: string; readonly note?: string; readonly changes?: unknown },
): Promise<void> {
  const detail = await client.get<ReviewDetailWire>(`/reviews/${documentId}`);
  // `openReviews` lists only the reviews still waiting (PENDING or IN_REVIEW).
  const target = detail.openReviews[0];
  if (!target) {
    throw new ApiError({ kind: 'conflict', status: 409, message: 'The document has no open review' });
  }
  await client.post<unknown>(`/reviews/${target.id}/decision`, decision, { idempotencyKey: true });
}

interface CatalogueWire {
  readonly provider: string;
  readonly category: string;
  readonly displayName: string;
  readonly implemented: boolean;
  readonly capabilities?: readonly string[];
  readonly ownerRequirements?: readonly string[];
  readonly note?: string | null;
}

interface MailAccountWire {
  readonly id: string;
  readonly display_name: string;
  readonly email_address: string;
  readonly provider: string;
  readonly status: string;
  readonly last_sync_at?: string | null;
  readonly last_sync_error?: string | null;
  readonly consecutive_failures?: number;
}

interface PlanWire {
  readonly code: string;
  readonly name: string;
  readonly price_cents: number;
  readonly currency: string;
  readonly is_active?: boolean;
  readonly tier?: number;
  readonly analyses_per_month?: number | null;
  readonly features?: string;
}

const planFromWire = (wire: PlanWire): PlanSummary => ({
  code: wire.code,
  name: wire.name,
  priceCents: wire.price_cents,
  currency: wire.currency,
  active: wire.is_active,
  tier: wire.tier,
  analysesPerMonth: wire.analyses_per_month ?? null,
  features: wire.features ? wire.features.split(',').filter(Boolean) : undefined,
});

type BillingStateWire = SubscriptionWire & {
  readonly owner: boolean;
  readonly invoiceAvailable: boolean;
  readonly cryptoAvailable: boolean;
  readonly plans: readonly PlanWire[];
};

const billingStateFromWire = (wire: BillingStateWire): BillingState => ({
  ...subscriptionFromWire(wire),
  owner: wire.owner,
  invoiceAvailable: wire.invoiceAvailable,
  cryptoAvailable: wire.cryptoAvailable,
  plans: wire.plans.map(planFromWire),
});

interface PlatformInvoiceWire {
  readonly id: string;
  readonly invoice_number: string;
  readonly plan_code: string;
  readonly period_months: number;
  readonly amount_cents: number;
  readonly status: BillingInvoice['status'];
  readonly method: BillingInvoice['method'];
  readonly reference?: string;
  readonly issued_at: string;
  readonly due_at: string;
  readonly paid_at?: string | null;
  readonly crypto_asset?: string | null;
  readonly crypto_network?: string | null;
  readonly crypto_address?: string | null;
  readonly crypto_amount?: number | string | null;
  readonly tenant_name?: string | null;
  readonly customer_name?: string | null;
  readonly customer_email?: string | null;
}

const platformInvoiceFromWire = (wire: PlatformInvoiceWire): BillingInvoice => ({
  id: wire.id,
  number: wire.invoice_number,
  planCode: wire.plan_code,
  months: wire.period_months,
  amountCents: wire.amount_cents,
  status: wire.status,
  method: wire.method,
  reference: wire.reference ?? wire.invoice_number,
  issuedAt: wire.issued_at,
  dueAt: wire.due_at,
  paidAt: wire.paid_at ?? null,
  cryptoAsset: wire.crypto_asset ?? null,
  cryptoNetwork: wire.crypto_network ?? null,
  cryptoAddress: wire.crypto_address ?? null,
  cryptoAmount: wire.crypto_amount === null || wire.crypto_amount === undefined ? null : String(wire.crypto_amount),
  tenantName: wire.tenant_name ?? null,
  customerName: wire.customer_name ?? null,
  customerEmail: wire.customer_email ?? null,
});

interface AdminTenantWire {
  readonly id: string;
  readonly name: string;
  readonly account_type: string;
  readonly created_at: string;
  readonly plan_code: string;
  readonly status: AdminTenant['status'];
  readonly access_until: string;
  readonly retention_until?: string | null;
  readonly data_choice?: 'KEEP' | 'DELETE' | null;
  readonly owner_email?: string | null;
  readonly members: number;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
const timelineEntry = (row: any): TimelineEntry => ({
  source: row.source,
  id: row.source_event_id,
  eventType: row.event_type,
  title: row.title ?? row.event_type,
  eventTime: row.event_time,
  recordedAt: row.recorded_at,
});

const relationshipItem = (row: any): RelationshipItem => ({
  id: row.id,
  source: row.source,
  type: row.type,
  status: row.status,
  documentId: row.document_id ?? row.other_document_id ?? null,
  documentName: row.document_name ?? null,
  reason: row.evidence?.rule ?? row.rationale?.de ?? null,
});

/** A 404 on a read of something that may not exist yet is an answer ("not made yet"), not a failure. */
async function orNull<T>(request: Promise<T>): Promise<T | null> {
  try {
    return await request;
  } catch (error) {
    if (isApiError(error) && error.kind === 'notFound') return null;
    throw error;
  }
}

const withSignal = (signal?: AbortSignal): { signal?: AbortSignal } => (signal ? { signal } : {});

/** The menu as the backend sends it; accepts camelCase and snake_case so a rename cannot blank the menu. */
interface NavigationPreferencesWire {
  readonly order?: readonly unknown[] | null;
  readonly hidden?: readonly unknown[] | null;
  readonly updatedAt?: string | null;
  readonly updated_at?: string | null;
}

/**
 * Only strings are ids. Unknown, repeated and malformed entries are dropped here; the screen registry is
 * the second gate, because an id the UI no longer knows must never hide or reorder a menu entry.
 */
function navigationPreferencesFromWire(
  wire: NavigationPreferencesWire | null | undefined,
): NavigationPreferences {
  const ids = (value: readonly unknown[] | null | undefined): readonly string[] => {
    const seen = new Set<string>();
    const result: string[] = [];
    for (const entry of value ?? []) {
      if (typeof entry !== 'string' || seen.has(entry)) continue;
      seen.add(entry);
      result.push(entry);
    }
    return result;
  };
  return {
    order: ids(wire?.order),
    hidden: ids(wire?.hidden),
    updatedAt: wire?.updatedAt ?? wire?.updated_at ?? null,
  };
}

export function createResources(client: ApiClient): ApiResources {
  return {
    auth: {
      ssoAvailable: async () => {
        try {
          return (await client.get<{ enabled?: boolean }>('/auth/oidc/available')).enabled === true;
        } catch {
          return false; // the page works without it
        }
      },
      login: async (payload) => {
        // A fresh sign-in must never be answered from the idempotency cache: the reply carries Set-Cookie.
        const answer = await client.post<{ mfaRequired?: boolean }>('/auth/login', {
          email: payload.email,
          password: payload.password,
        });
        // The session exists but is unusable until the code is proven; /auth/me would answer 401.
        if (answer?.mfaRequired) return { mfaRequired: true as const };
        return sessionUserFromMe(await client.get<MeResponse>('/auth/me'));
      },
      verifyMfa: async (code) => {
        await client.post<unknown>('/auth/mfa/verify', { code });
        return sessionUserFromMe(await client.get<MeResponse>('/auth/me'));
      },
      logout: () => client.post<void>('/auth/logout'),
      register: async (payload) => {
        const created = await client.post<{ userId: string }>(
          '/auth/register',
          {
            email: payload.email,
            password: payload.password,
            displayName: payload.fullName,
            acceptedTerms: payload.acceptedTerms,
          },
          { idempotencyKey: true },
        );
        return { userId: created.userId, email: payload.email };
      },
      verifyEmail: (payload) =>
        client.post<void>('/auth/verify-email', payload, { idempotencyKey: true }),
      resendVerification: (payload) =>
        client.post<void>('/auth/verify-email/resend', payload, { idempotencyKey: true }),
      requestPasswordReset: (payload) =>
        client.post<void>('/auth/password-reset', payload, { idempotencyKey: true }),
      confirmPasswordReset: (payload) =>
        client.post<void>('/auth/password-reset/confirm', payload, { idempotencyKey: true }),
      session: async (signal) =>
        sessionUserFromMe(await client.get<MeResponse>('/auth/me', withSignal(signal))),
      completeOnboarding: async (payload) => {
        await client.post<unknown>('/auth/onboarding', payload, { idempotencyKey: true });
        return sessionUserFromMe(await client.get<MeResponse>('/auth/me'));
      },
    },
    dashboard: {
      summary: (signal) => client.get<DashboardSummary>('/dashboard/summary', withSignal(signal)),
    },
    documents: {
      belegCounts: (signal) =>
        client.get<BelegCounts>('/documents/beleg-counts', withSignal(signal)),
      setBelegStatus: async (id, status) => {
        await client.patch<unknown>(`/documents/${id}/beleg-status`, { status });
      },
      batch: (ids, action, mandantId) =>
        client.post<BelegBatchResult>(
          '/documents/batch',
          { ids, action, ...(mandantId ? { mandantId } : {}) },
          { idempotencyKey: true },
        ),
      list: async (params, signal) => {
        // Screen filters are translated to the pipeline's vocabulary; one screen status covers several stages.
        const { status, channel, sort, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const page = await client.list<DocumentWire>(
          '/documents',
          {
            ...rest,
            status: typeof status === 'string' ? [...wireStatusesFor(status)] : undefined,
            source: typeof channel === 'string' ? [...wireSourcesFor(channel)] : undefined,
            sort: wireSort(typeof sort === 'string' ? sort : undefined),
          },
          withSignal(signal),
        );
        return { ...page, items: page.items.map(documentFromWire) };
      },
      export: async (params) => {
        const rows = await collectPages((page, size) => createResources(client).documents.list({ ...params, page, size }));
        return csvBlob(
          toCsv(rows, [
            { header: 'Titel', value: (row) => row.title },
            { header: 'Status', value: (row) => row.status },
            { header: 'Kanal', value: (row) => row.channel },
            { header: 'Eingang', value: (row) => row.receivedAt },
            { header: 'Größe (Bytes)', value: (row) => row.sizeBytes },
          ]),
        );
      },
      get: async (id, signal) => {
        const [document, fields, history] = await Promise.all([
          client.get<DocumentWire>(`/documents/${id}`, withSignal(signal)),
          client.get<FieldsWire>(`/documents/${id}/fields`, withSignal(signal)),
          // The history needs the audit permission, which not every role has: without it the tab is
          // empty, but the document still opens.
          client
            .get<AuditEventsWire>('/audit/events', {
              query: { entityType: 'documents', entityId: id, limit: 50 },
              ...withSignal(signal),
            })
            .catch(() => null),
        ]);
        return documentDetailFromWire(document, fields, history);
      },
      history: async (id, signal) => {
        const events = await client.get<AuditEventsWire>('/audit/events', {
          query: { entityType: 'documents', entityId: id, limit: 50 },
          ...withSignal(signal),
        });
        return normalizePage(events.events.map(historyFromWire));
      },
      upload: async (file, options) =>
        documentFromWire((await client.upload<UploadWire>('/documents', file, options)).document),
      removeMany: (ids) =>
        client.post<{ deleted: number; refused: string[] }>(
          '/documents/bulk-delete',
          { ids },
          { idempotencyKey: true },
        ),
      remove: async (id) => {
        await client.delete<unknown>(`/documents/${id}`, { idempotencyKey: true });
      },
      reprocess: async (id) => {
        await client.post<unknown>(`/documents/${id}/reprocess`, undefined, { idempotencyKey: true });
      },
      pipeline: (id, signal) => client.get<ProcessingFlow>(`/documents/${id}/pipeline`, withSignal(signal)),
      aiRead: (id) =>
        client.post<{ status: string; found: Record<string, string>; summary: string | null }>(
          `/documents/${id}/ai-read`,
          undefined,
          { idempotencyKey: true },
        ),
      detectedDates: (id, signal) =>
        client.get<{ dates: readonly DetectedDate[]; count: number; note: string }>(
          `/documents/${id}/detected-dates`,
          withSignal(signal),
        ),
      resort: () => client.post('/documents/resort', undefined, { idempotencyKey: true }),
      setType: async (id, typeCode) => {
        await client.post<unknown>(`/documents/${id}/type`, { typeCode }, { idempotencyKey: true });
      },
      mergeCandidates: (signal) => client.get<readonly MergeGroup[]>('/documents/merge-candidates', withSignal(signal)),
      merge: (documentIds, title) =>
        client.post('/documents/merge', { documentIds, title }, { idempotencyKey: true }),
      createTask: async (id) => {
        await client.post<unknown>(`/documents/${id}/create-task`, undefined, { idempotencyKey: true });
      },
      catalogue: (signal) => client.get<readonly CatalogueEntry[]>('/documents/types', withSignal(signal)),
      cases: (id, signal) => client.get<readonly DocumentCase[]>(`/documents/${id}/cases`, withSignal(signal)),
      assignMandant: async (id, mandantId) => {
        await client.patch<unknown>(`/documents/${id}/mandant`, { mandantId });
      },
      rerouteMandant: async (id) =>
        mandantRoutingFromWire(
          await client.post<MandantRoutingWire>(`/documents/${id}/mandant-reroute`, undefined, {
            idempotencyKey: true,
          }),
        ),
      categoryCounts: (signal) => client.get<Record<string, number>>('/documents/categories', withSignal(signal)),
      explanation: (id, language, signal) =>
        orNull(client.get<StoredExplanation>(`/documents/${id}/explanation`, { ...withSignal(signal), query: { language } })),
      explain: (id, language) =>
        client.post<ExplanationOutcome>(`/documents/${id}/explanation`, undefined, { query: { language } }),
      translation: (id, language, simple, signal) =>
        orNull(client.get<StoredExplanation>(`/documents/${id}/translation`, { ...withSignal(signal), query: { language, simple: String(simple) } })),
      translate: (id, language, simple) =>
        client.post<ExplanationOutcome>(`/documents/${id}/translation`, undefined, { query: { language, simple: String(simple) } }),
      downloadOriginal: (id) =>
        client.request<Blob>(`/documents/${id}/content`, { parse: 'blob', query: { download: 'true' } }),
      confirmFields: async (id, payload) => decideOpenReview(client, id, approvalFromFields(payload.fields)),
      archive: async (id) => {
        await client.post<unknown>(`/documents/${id}/status`, undefined, {
          query: { status: 'ARCHIVED' },
          idempotencyKey: true,
        });
      },
    },
    review: {
      queue: async (params, signal) => {
        const entries = await client.get<readonly ReviewQueueEntryWire[]>('/reviews', withSignal(signal));
        const size = typeof params?.size === 'number' ? params.size : entries.length;
        const shown = entries.slice(0, size);
        const items = await Promise.all(
          shown.map(async (entry) =>
            reviewItemFromWire(
              entry,
              await client.get<ReviewDetailWire>(`/reviews/${entry.documentId}`, withSignal(signal)),
            ),
          ),
        );
        return { items, page: 0, size: shown.length, totalItems: entries.length, totalPages: 1 };
      },
      confirm: (documentId, payload) =>
        decideOpenReview(client, documentId, approvalFromFields(payload.fields, payload.typeCode)),
      reject: (documentId, reason) =>
        decideOpenReview(client, documentId, { decision: 'REJECTED', note: reason }),
    },
    cases: {
      list: async (params, signal) => {
        const { status, priority, sort, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const page = await client.list<CaseWire>(
          '/cases',
          {
            ...rest,
            sort: sortFor(CASE_SORT, sort),
            status: typeof status === 'string' ? caseStatusesFor(status) : undefined,
            priority: typeof priority === 'string' ? prioritiesFor(priority) : undefined,
          },
          withSignal(signal),
        );
        return { ...page, items: page.items.map((item) => caseFromWire(item)) };
      },
      detail: async (id, signal) => {
        const wire = await client.get<{
          readonly caseRecord: CaseDetail & { readonly typeName?: string | null };
          readonly documents?: CaseDetail['documents'];
          readonly timeline?: CaseDetail['timeline'];
          readonly requiredDocuments?: readonly string[] | null;
        }>(`/cases/${id}`, withSignal(signal));
        const record = wire.caseRecord;
        return {
          id: record.id,
          reference: record.reference,
          title: record.title,
          status: record.status,
          priority: record.priority ?? null,
          typeName: record.typeName ?? null,
          description: record.description ?? null,
          dueAt: record.dueAt ?? null,
          openedAt: record.openedAt ?? null,
          responsibleName: record.responsibleName ?? null,
          documents: wire.documents ?? [],
          timeline: wire.timeline ?? [],
          requiredDocuments: wire.requiredDocuments ?? [],
        };
      },
      deadlines: async (id, signal) => {
        const wire = await client.get<readonly (Omit<CaseDeadline, 'dueDate'> & { readonly dueDate?: string | null })[]>(
          `/cases/${id}/deadlines`,
          withSignal(signal),
        );
        return wire.map((row) => ({
          id: row.id,
          type: row.type,
          dueDate: row.dueDate ?? null,
          source: row.source,
          confirmation: row.confirmation === 'CONFIRMED' ? 'CONFIRMED' : 'UNCONFIRMED',
          sourceDocumentId: row.sourceDocumentId ?? null,
          sourcePage: row.sourcePage ?? null,
          sourceSnippet: row.sourceSnippet ?? null,
          ruleVersion: row.ruleVersion ?? null,
          note: row.note ?? null,
        }));
      },
      workspace: async (id, signal) => {
        const workspace = await client.get<Parameters<typeof workspaceFromWire>[0]>(`/cases/${id}/workspace`, withSignal(signal));
        return workspaceFromWire(workspace, null);
      },
      setGoal: async (id, goal, nextAction) => {
        await client.put<unknown>(`/cases/${id}/goal`, { goal, nextAction });
      },
      addCriterion: async (id, payload) => {
        await client.post<unknown>(`/cases/${id}/criteria`, payload, { idempotencyKey: true });
      },
      meetCriterion: async (id, criterionId, note) => {
        await client.post<unknown>(`/cases/${id}/criteria/${criterionId}/met`, { evidenceType: 'NOTE', note }, { idempotencyKey: true });
      },
      waiveCriterion: async (id, criterionId, reason) => {
        await client.post<unknown>(`/cases/${id}/criteria/${criterionId}/waive`, { reason }, { idempotencyKey: true });
      },
      get: async (id, signal) =>
        caseDetailFromWire(await client.get<CaseDetailWire>(`/cases/${id}`, withSignal(signal))),
      create: async (payload) =>
        caseFromWire(
          await client.post<CaseWire>(
            '/cases',
            { title: payload.title, caseTypeCode: payload.caseType },
            { idempotencyKey: true },
          ),
        ),
    },
    tasks: {
      list: async (params, signal) => {
        const { status, priority, sort, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const page = await client.list<TaskWire>(
          '/tasks',
          {
            ...rest,
            sort: sortFor(TASK_SORT, sort),
            status: typeof status === 'string' ? taskStatusesFor(status) : undefined,
            priority: typeof priority === 'string' ? prioritiesFor(priority) : undefined,
          },
          withSignal(signal),
        );
        return { ...page, items: page.items.map((item) => taskFromWire(item)) };
      },
      create: async (payload) =>
        taskFromWire(
          await client.post<TaskWire>(
            '/tasks',
            {
              title: payload.title,
              priority: priorityToWire(payload.priority),
              dueAt: dueInstant(payload.dueDate),
              assigneeUserId: payload.assigneeId,
              caseId: payload.caseId,
              completionCriteria: payload.completionCriteria,
            },
            { idempotencyKey: true },
          ),
        ),
      detail: async (id, signal) => {
        const wire = await client.get<{
          readonly task: {
            readonly id: string; readonly title: string; readonly description?: string | null; readonly status: string;
            readonly dueAt?: string | null; readonly completionCriteria?: string | null; readonly requiresApproval: boolean;
            readonly approvedAt?: string | null; readonly sourceDocumentId?: string | null; readonly caseId?: string | null;
            readonly caseReference?: string | null;
          };
          readonly checklist: readonly { readonly id: string; readonly label: string; readonly required: boolean; readonly done: boolean }[];
        }>(`/tasks/${id}`, withSignal(signal));
        const task = wire.task;
        return {
          id: task.id,
          title: task.title,
          description: task.description ?? null,
          status: task.status,
          finished: task.status === 'DONE' || task.status === 'CANCELLED',
          dueAt: task.dueAt ?? null,
          completionCriteria: task.completionCriteria ?? null,
          requiresApproval: task.requiresApproval,
          approved: !!task.approvedAt,
          sourceDocumentId: task.sourceDocumentId ?? null,
          caseId: task.caseId ?? null,
          caseReference: task.caseReference ?? null,
          checklist: wire.checklist,
        };
      },
      tick: async (itemId, done) => {
        await client.post<unknown>(`/tasks/checklist/${itemId}/tick`, undefined, {
          query: { done: String(done) },
          idempotencyKey: true,
        });
      },
      complete: async (id, options) => {
        await client.post<unknown>(
          `/tasks/${id}/complete`,
          { note: options?.note, acknowledgeOpenItems: options?.acknowledgeOpenItems ?? false },
          { idempotencyKey: true },
        );
      },
      setStatus: async (id, status) =>
        taskFromWire(
          status === 'done'
            ? // Finishing a task means checking its criteria, so it has its own endpoint.
              await client.post<TaskWire>(`/tasks/${id}/complete`, {}, { idempotencyKey: true })
            : await client.post<TaskWire>(
                `/tasks/${id}/status`,
                {
                  status: taskStatusToWire(status),
                  // Blocking needs a reason; the list has no field for one, so the origin is recorded.
                  reason: status === 'blocked' ? 'Über die Aufgabenliste blockiert' : undefined,
                },
                { idempotencyKey: true },
              ),
        ),
    },
    calendar: {
      events: async (params, signal) => {
        const rows = await client.get<readonly CalendarEventWire[]>('/calendar/events', {
          query: { from: params?.from, to: params?.to },
          ...withSignal(signal),
        });
        return normalizePage(rows.map(calendarEventFromWire));
      },
      create: async (payload) =>
        calendarEventFromWire(
          await client.post<CalendarEventWire>(
            '/calendar/events',
            { title: payload.title, date: payload.date, type: payload.type.toUpperCase() },
            { idempotencyKey: true },
          ),
        ),
      feeds: {
        list: (signal) =>
          client.get<
            readonly { id: string; label: string; locale: string; lastUsedAt: string | null }[]
          >('/calendar/feeds', withSignal(signal)),
        create: (label, locale) =>
          client.post<{ id: string; label: string; token: string }>(
            '/calendar/feeds',
            { label, locale },
            { idempotencyKey: true },
          ),
        revoke: async (id) => {
          await client.request<unknown>(`/calendar/feeds/${id}`, { method: 'DELETE', parse: 'none' });
        },
      },
    },
    invoices: {
      // The two documents the backend can produce for an invoice. They were reachable through the API and
      // through nothing a person uses, which is the gap this product keeps producing.
      pdfUrl: (id) => `/api/v1/invoices/${id}/pdf`,
      xrechnungUrl: (id) => `/api/v1/invoices/${id}/xrechnung`,
      // Rendering the PDF, archiving it, writing the letter and handing it over is one call: splitting it
      // on the client would let a click stop between "document stored" and "letter sent" with no record.
      send: (id, payload) =>
        client.post<InvoiceSendResult>(`/invoices/${id}/send`, payload ?? {}, { idempotencyKey: true }),
      list: async (params, signal) => {
        const { status, direction, sort, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const page = await client.list<InvoiceWire>(
          '/invoices',
          {
            ...rest,
            sort: sortFor(INVOICE_SORT, sort),
            status: typeof status === 'string' ? invoiceStatusesFor(status) : undefined,
            direction:
              typeof direction === 'string'
                ? directionToWire(direction as 'incoming' | 'outgoing')
                : undefined,
          },
          withSignal(signal),
        );
        return { ...page, items: page.items.map(invoiceFromWire) };
      },
      approve: async (id) =>
        invoiceFromWire(
          await client.post<InvoiceWire>(`/invoices/${id}/approve`, {}, { idempotencyKey: true }),
        ),
      create: async (payload) =>
        invoiceFromWire(
          await client.post<InvoiceWire>(
            '/invoices',
            {
              invoiceNumber: payload.number,
              supplierName: payload.counterparty,
              direction: directionToWire(payload.direction),
              currency: 'EUR',
              grossAmount: decimalFromInput(payload.amount),
              dueDate: payload.dueDate,
            },
            { idempotencyKey: true },
          ),
        ),
    },
    payments: {
      list: async (params, signal) => {
        const { status, sort: _ignored, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const page = await client.list<BankTransactionWire>(
          '/banking/transactions',
          { ...rest, status: typeof status === 'string' ? paymentStatusesFor(status) : undefined },
          withSignal(signal),
        );
        return { ...page, items: page.items.map(paymentFromWire) };
      },
      match: async (id, invoiceId) => {
        // The amount is left out on purpose: without one the whole transaction is matched.
        await client.post<unknown>(
          '/banking/matches',
          { transactionId: id, invoiceId, matchType: 'MANUAL' },
          { idempotencyKey: true },
        );
      },
    },
    contacts: {
      list: async (params, signal) => {
        const { contactType, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const page = await client.list<ContactWire>(
          '/contacts',
          {
            ...rest,
            kind: typeof contactType === 'string' ? contactKindToWire(contactType as 'person') : undefined,
          },
          withSignal(signal),
        );
        return { ...page, items: page.items.map(contactFromWire) };
      },
      create: async (payload) =>
        contactFromWire(
          await client.post<ContactWire>(
            '/contacts',
            { name: payload.name, kind: contactKindToWire(payload.contactType), email: payload.email },
            { idempotencyKey: true },
          ),
        ),
    },
    mandanten: {
      list: async (params, signal) => {
        const rows = await client.get<readonly MandantWire[]>('/mandanten', {
          query: {
            ...(params?.q ? { q: params.q } : {}),
            ...(params?.onlyProblems ? { onlyProblems: 'true' } : {}),
          },
          ...withSignal(signal),
        });
        return rows.map(mandantFromWire);
      },
      get: async (id, signal) =>
        mandantDetailFromWire(
          await client.get<MandantDetailWire>(
            `/mandanten/${encodeURIComponent(id)}`,
            withSignal(signal),
          ),
        ),
    },
    mandantRequests: {
      list: (params, signal) =>
        client.get<readonly MandantRequest[]>('/mandant-requests', {
          query: {
            ...(params?.mandantId ? { mandantId: params.mandantId } : {}),
            ...(params?.documentId ? { documentId: params.documentId } : {}),
            ...(params?.status ? { status: params.status } : {}),
          },
          ...withSignal(signal),
        }),
      get: (id, signal) =>
        client.get<MandantRequest>(`/mandant-requests/${encodeURIComponent(id)}`, withSignal(signal)),
      preview: (payload) =>
        client.post<MandantRequestPreview>('/mandant-requests/preview', payload),
      create: (payload) =>
        client.post<MandantRequest>('/mandant-requests', payload, { idempotencyKey: true }),
      update: (id, payload) =>
        client.patch<MandantRequest>(`/mandant-requests/${encodeURIComponent(id)}`, payload),
      send: (id) =>
        client.post<MandantRequest>(`/mandant-requests/${encodeURIComponent(id)}/send`, {}),
      cancel: (id) =>
        client.post<MandantRequest>(`/mandant-requests/${encodeURIComponent(id)}/cancel`, {}),
      markAnswered: (id) =>
        client.post<MandantRequest>(
          `/mandant-requests/${encodeURIComponent(id)}/mark-answered`,
          {},
        ),
    },
    exports: {
      datev: {
        list: (signal) =>
          client.get<readonly DatevExportBatch[]>('/exports/datev', withSignal(signal)),
        create: (payload) =>
          client.post<DatevExportResult>('/exports/datev', payload, { idempotencyKey: true }),
        download: (id) =>
          client.get<Blob>(`/exports/datev/${encodeURIComponent(id)}/download`, { parse: 'blob' }),
      },
    },
    mail: {
      get: (id, signal) => client.get<MailMessageDetail>(`/mail/messages/${id}`, withSignal(signal)),
      list: async (params, signal) => {
        const { folder, status, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        // The archive folder has no backing store yet: asking for it is an empty answer, not "everything".
        if (folder === 'archive') return normalizePage([]);
        const page = await client.list<MailWire>(
          '/mail/messages',
          {
            ...rest,
            folder: typeof folder === 'string' ? mailFolderToWire(folder) : undefined,
            status: typeof status === 'string' ? mailStatusToWire(status) : undefined,
          },
          withSignal(signal),
        );
        return { ...page, items: page.items.map(mailFromWire) };
      },
    },
    generatedDocuments: {
      list: async (_params, signal) => {
        // The route answers {documents, count}; the table wants the paged shape. This mapping is the fix for
        // the screen that shipped broken: client.list expects {items}, got undefined, and the page reported
        // that the data could not be loaded. Nothing tested the page, so nothing noticed.
        const body = await client.get<{
          documents: readonly GeneratedDocumentSummary[];
          count: number;
        }>('/generated-documents', withSignal(signal));
        return {
          items: body.documents,
          page: 1,
          size: body.count,
          totalItems: body.count,
          totalPages: 1,
        };
      },
      readiness: (id, signal) =>
        client.get<GeneratedDocumentReadiness>(`/generated-documents/${id}/readiness`, withSignal(signal)),
      downloadUrl: (id) => `/api/v1/generated-documents/${id}/download`,
    },
    templates: {
      list: async (params, signal) => {
        const { status, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const page = await client.list<TemplateWire>(
          '/templates',
          { ...rest, status: typeof status === 'string' ? status.toUpperCase() : undefined },
          withSignal(signal),
        );
        return { ...page, items: page.items.map(templateFromWire) };
      },
      create: async (payload) => {
        const body = payload.body ?? '';
        const created = await client.post<{ templateId: string }>(
          '/templates',
          {
            code: templateCode(payload.name),
            nameGerman: payload.name,
            category: templateCategoryToWire(payload.templateType),
            bodyHtml: body,
            // Every placeholder in the text becomes a field the person fills in: the backend refuses a
            // body that uses a placeholder nobody declared, and typing {{name}} is declaration enough.
            fields: placeholdersIn(body).map((key) => ({
              fieldKey: key,
              labelGerman: key,
              sourceEntity: 'CUSTOM',
              valueType: 'TEXT',
              required: false,
            })),
          },
          { idempotencyKey: true },
        );
        return {
          id: created.templateId,
          name: payload.name,
          templateType: templateCategoryToWire(payload.templateType),
          status: 'draft' as const,
          updatedAt: new Date().toISOString(),
          body,
          placeholders: placeholdersIn(body),
        };
      },
    },
    guide: {
      ask: (payload) => client.post('/ai/guide', payload, { timeoutMs: 45_000 }),
    },
    automations: {
      list: async (params, signal) => {
        const { status } = (params ?? {}) as Record<string, string | undefined>;
        const rows = await client.get<readonly WorkflowWire[]>('/workflows', withSignal(signal));
        const shown = typeof status === 'string' ? rows.filter((row) => automationStatusMatches(row, status)) : rows;
        return normalizePage(shown.map(automationFromWire));
      },
      create: async (payload) => {
        const created = await client.post<{ definitionId: string }>(
          '/workflows',
          {
            code: automationCode(payload.name),
            name: payload.name,
            category: automationStartKind(payload.trigger),
            triggerConfig: { type: payload.trigger },
          },
          { idempotencyKey: true },
        );
        return {
          id: created.definitionId,
          name: payload.name,
          trigger: payload.trigger,
          status: 'draft' as const,
          lastRunAt: null,
          lastRunResult: null,
          runsLast30Days: 0,
        };
      },
    },
    integrations: {
      list: async (params, signal) => {
        const rows = await client.get<readonly IntegrationWire[]>('/integrations', withSignal(signal));
        void params;
        return normalizePage(rows.map(integrationFromWire));
      },
      disconnect: async (id) => {
        await client.post<unknown>(`/integrations/${id}/disable`, {}, { idempotencyKey: true });
      },
      catalogue: async (signal) => {
        const grouped = await client.get<Record<string, unknown>>('/integrations/catalogue', withSignal(signal));
        const entries: IntegrationCatalogueEntry[] = [];
        for (const value of Object.values(grouped)) {
          if (!Array.isArray(value)) continue; // implementedCount / notImplementedCount
          for (const raw of value as readonly CatalogueWire[]) {
            entries.push({
              provider: raw.provider,
              category: raw.category,
              name: raw.displayName,
              implemented: raw.implemented,
              capabilities: raw.capabilities ?? [],
              ownerRequirements: raw.ownerRequirements ?? [],
              note: raw.note ?? null,
            });
          }
        }
        return entries;
      },
    },
    mailAccounts: {
      list: async (signal) => {
        const rows = await client.get<readonly MailAccountWire[]>('/mail/accounts', withSignal(signal));
        return rows.map((row) => ({
          id: row.id,
          name: row.display_name,
          email: row.email_address,
          provider: row.provider,
          status: row.status,
          lastSyncAt: row.last_sync_at ?? null,
          lastError: row.last_sync_error ?? null,
          failures: row.consecutive_failures ?? 0,
        }));
      },
      connect: (payload) =>
        client.post<{ accountId: string }>(
          '/mail/accounts',
          {
            displayName: payload.displayName,
            emailAddress: payload.emailAddress,
            provider: 'IMAP',
            imapHost: payload.imapHost,
            imapPort: payload.imapPort,
            imapUsername: payload.emailAddress,
            imapUseSsl: true,
            password: payload.password,
          },
          { idempotencyKey: true },
        ),
      collect: (accountId) =>
        client.post<MailCollectResult>(`/mail/accounts/${accountId}/collect`, {}, { idempotencyKey: true }),
    },
    mailOAuth: {
      providers: (signal) =>
        client.get<readonly MailOAuthProviderSummary[]>('/mail/oauth/providers', withSignal(signal)),
      // The route redirects to the provider, so it must be a navigation and not a fetch.
      startPath: (provider) => `/api/v1/mail/oauth/${encodeURIComponent(provider)}/start`,
    },
    publicPlans: {
      get: async (signal) => {
        const wire = await client.get<{
          trialDays: number;
          retentionDays: number;
          plans: readonly PlanWire[];
        }>('/public/plans', withSignal(signal));
        return { trialDays: wire.trialDays, retentionDays: wire.retentionDays, plans: wire.plans.map(planFromWire) };
      },
    },
    billing: {
      state: async (signal) =>
        billingStateFromWire(await client.get<BillingStateWire>('/billing/subscription', withSignal(signal))),
      changePlan: async (planCode) =>
        billingStateFromWire(
          await client.post<BillingStateWire>('/billing/plan', { planCode }, { idempotencyKey: true }),
        ),
      invoices: async (signal) =>
        (await client.get<readonly PlatformInvoiceWire[]>('/billing/invoices', withSignal(signal))).map(platformInvoiceFromWire),
      createInvoice: async (payload) =>
        platformInvoiceFromWire(await client.post<PlatformInvoiceWire>('/billing/invoices', payload, { idempotencyKey: true })),
      invoicePdfUrl: (id) => `/api/v1/billing/invoices/${id}/pdf`,
      exportUrl: () => '/api/v1/billing/export',
      keepData: async () => {
        await client.post<unknown>('/billing/data-choice', { choice: 'KEEP' }, { idempotencyKey: true });
      },
      deleteData: async () => {
        await client.post<unknown>('/billing/data-choice', { choice: 'DELETE', confirm: 'DELETE' }, { idempotencyKey: true });
      },
    },
    admin: {
      overview: (signal) => client.get<AdminOverview>('/admin/overview', withSignal(signal)),
      tenants: async (params, signal) =>
        (await client.get<readonly AdminTenantWire[]>('/admin/tenants', { ...withSignal(signal), query: params })).map(
          (row) => ({
            id: row.id,
            name: row.name,
            accountType: row.account_type,
            createdAt: row.created_at,
            planCode: row.plan_code,
            status: row.status,
            accessUntil: row.access_until,
            retentionUntil: row.retention_until ?? null,
            dataChoice: row.data_choice ?? null,
            ownerEmail: row.owner_email ?? null,
            members: row.members,
          }),
        ),
      extend: async (id, months) => {
        await client.post<unknown>(`/admin/tenants/${id}/extend`, { months }, { idempotencyKey: true });
      },
      grantDays: async (id, days) => {
        await client.post<unknown>(`/admin/tenants/${id}/grant-days`, { days }, { idempotencyKey: true });
      },
      setSuspended: async (id, suspended) => {
        if (suspended) {
          await client.post<unknown>(`/admin/tenants/${id}/suspend`, {}, { idempotencyKey: true });
        } else {
          await client.post<unknown>(`/admin/tenants/${id}/unsuspend`, {}, { idempotencyKey: true });
        }
      },
      setPlan: async (id, planCode) => {
        await client.post<unknown>(`/admin/tenants/${id}/plan`, { planCode }, { idempotencyKey: true });
      },
      deleteTenant: async (id, confirmName) => {
        await client.post<unknown>(`/admin/tenants/${id}/delete`, {}, { idempotencyKey: true, query: { confirmName } });
      },
      plans: async (signal) =>
        (await client.get<readonly PlanWire[]>('/admin/plans', withSignal(signal))).map(planFromWire),
      editPlan: async (code, payload) => {
        await client.put<unknown>(`/admin/plans/${code}`, payload);
      },
      settings: (signal) => client.get<Record<string, unknown>>('/admin/settings', withSignal(signal)),
      putSetting: (key, value) => client.put<Record<string, unknown>>('/admin/settings', { key, value }),
      invoices: async (status, signal) =>
        (
          await client.get<readonly PlatformInvoiceWire[]>('/admin/invoices', {
            ...withSignal(signal),
            query: status ? { status } : undefined,
          })
        ).map(platformInvoiceFromWire),
      markPaid: async (id) => {
        await client.post<unknown>(`/admin/invoices/${id}/mark-paid`, {}, { idempotencyKey: true });
      },
      cancelInvoice: async (id) => {
        await client.post<unknown>(`/admin/invoices/${id}/cancel`, {}, { idempotencyKey: true });
      },
      invoicePdfUrl: (id) => `/api/v1/admin/invoices/${id}/pdf`,
      runLifecycle: async () => {
        await client.post<unknown>('/admin/lifecycle/run', {}, { idempotencyKey: true });
      },
      services: (signal) => client.get<readonly AdminService[]>('/admin/integrations', withSignal(signal)),
    },
    devices: {
      list: async (signal) => {
        const rows = await client.get<readonly DeviceWire[]>('/devices', withSignal(signal));
        return normalizePage(rows.map((row) => deviceFromWire(row)));
      },
      scan: (id, profile) =>
        client.post<void>(`/devices/${id}/scan`, { profile }, { idempotencyKey: true }),
    },
    telegram: {
      status: (signal) => client.get<TelegramStatus>('/telegram/status', withSignal(signal)),
      createLinkCode: () => client.post<TelegramStatus>('/telegram/link-code', {}, { idempotencyKey: true }),
      setPreferences: async (payload) => {
        await client.put<unknown>('/telegram/preferences', payload, { idempotencyKey: true });
      },
      test: async () => {
        await client.post<unknown>('/telegram/test', {}, { idempotencyKey: true });
      },
      unlink: async () => {
        await client.delete<unknown>('/telegram/link', { idempotencyKey: true });
      },
    },
    memory: {
      profile: async (contactId, signal) => {
        const wire = await client.get<Record<string, any>>(`/memory/entities/${contactId}`, withSignal(signal));
        const c = wire.contact as Record<string, any>;
        return {
          contact: { id: c.id, name: c.display_name, email: c.email ?? null, phone: c.phone ?? null, isCustomer: c.is_customer === true, isSupplier: c.is_supplier === true },
          identifiers: (wire.identifiers ?? []).map((i: any) => ({ id: i.id, kind: i.kind, value: i.value })),
          documents: (wire.documents ?? []).map((d: any) => ({ relationshipId: d.relationship_id, documentId: d.document_id, name: d.name, status: d.status, score: d.score ?? null })),
          contracts: (wire.contracts ?? []).map((k: any) => ({ id: k.id, title: k.title, status: k.status, noticeDeadline: k.notice_deadline_date ?? null })),
          invoices: (wire.invoices ?? []).map((i: any) => ({ id: i.id, number: i.invoice_number ?? null, gross: i.gross_amount ?? null, currency: i.currency, status: i.status })),
          cases: (wire.cases ?? []).map((k: any) => ({ id: k.id, reference: k.reference, title: k.title, status: k.status })),
          openFindings: (wire.openFindings ?? []).map((f: any) => ({ id: f.id, title: f.title, severity: f.severity })),
        };
      },
      contactTimeline: async (contactId, signal) =>
        (await client.get<any[]>(`/memory/entities/${contactId}/timeline`, withSignal(signal))).map(timelineEntry),
      contractTimeline: async (contractId, signal) =>
        (await client.get<any[]>(`/memory/contracts/${contractId}/timeline`, withSignal(signal))).map(timelineEntry),
      contractRelationships: async (contractId, signal) =>
        (await client.get<any[]>(`/memory/contracts/${contractId}/relationships`, withSignal(signal))).map(relationshipItem),
      documentRelationships: async (documentId, signal) =>
        (await client.get<any[]>(`/memory/documents/${documentId}/relationships`, withSignal(signal))).map(relationshipItem),
      invoice: async (invoiceId, signal) => {
        const i = await client.get<any>(`/invoices/${invoiceId}`, withSignal(signal));
        return {
          id: i.id,
          number: i.invoiceNumber ?? null,
          supplierName: i.supplierName ?? null,
          gross: i.grossAmount === null || i.grossAmount === undefined ? null : String(i.grossAmount),
          currency: i.currency,
          status: i.status,
          dueDate: i.dueDate ?? null,
          documentId: i.documentId ?? null,
          bankDetailsChanged: i.bankDetailsChanged === true,
          flags: (i.flags ?? []).map((f: any) => String(f.message ?? f.code)),
        };
      },
      confirmRelationship: async (id) => {
        await client.post<unknown>(`/memory/relationships/${id}/confirm`, undefined, { idempotencyKey: true });
      },
      rejectRelationship: async (id) => {
        await client.post<unknown>(`/memory/relationships/${id}/reject`, undefined, { idempotencyKey: true });
      },
      addIdentifier: async (contactId, kind, value) => {
        await client.post<unknown>(`/contacts/${contactId}/identifiers`, { kind, value }, { idempotencyKey: true });
      },
      removeIdentifier: async (contactId, identifierId) => {
        await client.delete<unknown>(`/contacts/${contactId}/identifiers/${identifierId}`);
      },
    },
    ruleSettings: {
      list: (signal) => client.get<readonly RuleSetting[]>('/settings/rules', withSignal(signal)),
      change: async (key, value, reason) => {
        await client.put<unknown>(`/settings/rules/${encodeURIComponent(key)}`, { value, reason });
      },
      reset: async (key, reason) => {
        await client.post<unknown>(`/settings/rules/${encodeURIComponent(key)}/reset`, { reason });
      },
      history: async (key, signal) =>
        (
          await client.get<
            readonly { version: number; previous_value: string | null; new_value: string; change_reason: string | null; created_at: string }[]
          >(`/settings/rules/${encodeURIComponent(key)}/history`, withSignal(signal))
        ).map((row) => ({
          version: row.version,
          previousValue: row.previous_value,
          newValue: row.new_value,
          reason: row.change_reason,
          changedAt: row.created_at,
        })),
    },
    approvals: {
      center: async (signal) =>
        (await client.get<Parameters<typeof approvalFromWire>[0][]>('/approvals/center', withSignal(signal))).map((row) => approvalFromWire(row)),
      decide: async (id, decision) => {
        await client.post<unknown>(
          `/approvals/${id}/decision`,
          { approved: decision.kind === 'approve', changesRequested: decision.kind === 'changes', note: decision.note },
          { idempotencyKey: true },
        );
      },
    },
    demo: {
      start: (persona) => client.post('/public/demo/start', { persona }),
      status: (signal) => client.get('/demo/status', withSignal(signal)),
      exit: async () => {
        await client.post<unknown>('/demo/exit', undefined, { idempotencyKey: true });
      },
    },
    legal: {
      get: (signal) => client.get<LegalInfo>('/public/legal', withSignal(signal)),
    },
    profile: {
      get: (signal) => client.get<ProfileData>('/profile', withSignal(signal)),
      update: (values) => client.put<ProfileData>('/profile', values, { idempotencyKey: true }),
      uploadAvatar: async (file) => {
        const form = new FormData();
        form.append('file', file, 'avatar');
        await client.put<unknown>('/profile/avatar', form, { idempotencyKey: true });
      },
      removeAvatar: async () => {
        await client.delete<unknown>('/profile/avatar', { idempotencyKey: true });
      },
    },
    workspaces: {
      list: (signal) => client.get<WorkspaceList>('/workspaces', withSignal(signal)),
      switchTo: async (tenantId) => {
        await client.post<unknown>(`/workspaces/${tenantId}/switch`, undefined, { idempotencyKey: true });
      },
      createClient: (name) => client.post<WorkspaceEntry>('/workspaces', { name }, { idempotencyKey: true }),
    },
    deletion: {
      remove: (kind, ids) =>
        client.post<{ deleted: number; refused: string[] }>('/bulk-delete', { kind, ids }, { idempotencyKey: true }),
    },
    members: {
      list: async (params, signal) => {
        void params;
        const [members, invitations] = await Promise.all([
          client.get<readonly MemberWire[]>('/members', withSignal(signal)),
          // Listing invitations needs the administration right; without it only the members are shown.
          client
            .get<readonly PendingInvitationWire[]>('/invitations', withSignal(signal))
            .catch(() => [] as readonly PendingInvitationWire[]),
        ]);
        return normalizePage([...members.map(memberFromWire), ...invitations.map(invitedMemberFromWire)]);
      },
      invite: async (payload) => {
        await client.post<unknown>(
          '/invitations',
          { email: payload.email, roleCode: roleToWire(payload.role) },
          { idempotencyKey: true },
        );
      },
      setRole: async (id, role) => {
        await client.post<unknown>(`/members/${id}/role`, { roleCode: roleToWire(role) }, { idempotencyKey: true });
        const members = await client.get<readonly MemberWire[]>('/members');
        const changed = members.find((member) => member.user_id === id);
        if (!changed) throw new ApiError({ kind: 'notFound', status: 404, message: 'The member was not found' });
        return memberFromWire(changed);
      },
    },
    reports: {
      get: async (type, period, signal) => {
        const [from, to] = period.split('..');
        const report = await client.get<{
          readonly metrics: readonly { readonly label: string; readonly value: number }[];
        }>(`/reports/${type}`, { query: { from, to }, ...withSignal(signal) });
        return {
          type,
          period,
          generatedAt: new Date().toISOString(),
          // The backend reports figures with their unit and whether they are exact; the screen shows the
          // figure under its label, and the label already carries the unit.
          kpis: Object.fromEntries(report.metrics.map((metric) => [metric.label, Number(metric.value)])),
          series: report.metrics.map((metric) => ({ period: metric.label, value: Number(metric.value) })),
        };
      },
      export: async (type, period) => {
        const report = await createResources(client).reports.get(type, period);
        return csvBlob(
          toCsv(report.series, [
            { header: 'Kennzahl', value: (row) => row.period },
            { header: 'Wert', value: (row) => row.value },
          ]),
        );
      },
    },
    archive: {
      list: async (params, signal) => {
        const { sort, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const page = await client.list<DocumentWire>(
          '/documents',
          {
            ...rest,
            status: ['ARCHIVED', 'LEGAL_HOLD'],
            sort: wireSort(typeof sort === 'string' ? sort : undefined),
          },
          withSignal(signal),
        );
        return {
          ...page,
          items: page.items.map((document) => ({
            id: document.id,
            title: document.originalFilename,
            archivedAt: null,
            // The retention end is derived from a confirmed retention policy; until one is applied the
            // entry says so instead of inventing a period.
            retentionUntil: null,
            legalHold: document.processingStatus === 'LEGAL_HOLD' || document.legalHold === true,
            status: 'unknown' as const,
          })),
        };
      },
      export: async (params) => {
        const rows = await collectPages((page, size) => createResources(client).archive.list({ ...params, page, size }));
        return csvBlob(
          toCsv(rows, [
            { header: 'Titel', value: (row) => row.title },
            { header: 'Rechtliche Sperre', value: (row) => (row.legalHold ? 'ja' : 'nein') },
            { header: 'Aufbewahrung bis', value: (row) => row.retentionUntil },
          ]),
        );
      },
    },
    notifications: {
      list: async (params, signal) => {
        // The inbox endpoint answers with the caller's whole list, newest first; category filter and paging
        // are applied here, which is cheap for one person's notifications.
        const { category, page = 0, size = 50 } = (params ?? {}) as Record<string, string | number | undefined>;
        const rows = (await client.get<readonly NotificationWire[]>('/notifications', withSignal(signal))).map(
          notificationFromWire,
        );
        const shown = typeof category === 'string' ? rows.filter((row) => row.category === category) : rows;
        const pageIndex = Number(page);
        const pageSize = Number(size);
        return {
          items: shown.slice(pageIndex * pageSize, (pageIndex + 1) * pageSize),
          page: pageIndex,
          size: pageSize,
          totalItems: shown.length,
          totalPages: Math.max(1, Math.ceil(shown.length / pageSize)),
        };
      },
      markRead: async (id) => {
        await client.post<unknown>(`/notifications/${id}/read`, {});
      },
      markAllRead: async () => {
        await client.post<unknown>('/notifications/read-all', {});
      },
      unreadCount: async (signal) =>
        (await client.get<{ unread: number }>('/notifications/unread-count', withSignal(signal))).unread,
      preferences: async (signal) =>
        preferencesFromWire(await client.get<readonly PreferenceWire[]>('/notifications/preferences', withSignal(signal))),
      savePreferences: async (preferences) =>
        preferencesFromWire(
          await client.put<readonly PreferenceWire[]>('/notifications/preferences', preferencesToWire(preferences)),
        ),
    },
    audit: {
      list: async (params, signal) => {
        // The audit API returns the newest events up to a limit rather than pages: ask for as many as the
        // requested page needs and cut the page out here.
        const { page = 0, size = 25, ...rest } = (params ?? {}) as Record<string, string | number | undefined>;
        const pageIndex = Number(page);
        const pageSize = Number(size);
        const answer = await client.get<{ readonly events: readonly AuditEventWire[] }>('/audit/events', {
          query: { ...rest, limit: (pageIndex + 1) * pageSize + 1 },
          ...withSignal(signal),
        });
        const start = pageIndex * pageSize;
        const items = answer.events.slice(start, start + pageSize).map(auditEntryFromWire);
        const more = answer.events.length > start + pageSize;
        return {
          items,
          page: pageIndex,
          size: pageSize,
          // One more event than the page needs tells whether a next page exists without counting the log.
          totalItems: start + items.length + (more ? 1 : 0),
          totalPages: pageIndex + 1 + (more ? 1 : 0),
        };
      },
      export: async (params) => {
        const answer = await client.get<{ readonly events: readonly AuditEventWire[] }>('/audit/events', {
          query: { ...params, limit: 5000 },
        });
        return csvBlob(
          toCsv(answer.events.map(auditEntryFromWire), [
            { header: 'Zeitpunkt', value: (row) => row.timestamp },
            { header: 'Person', value: (row) => row.actor },
            { header: 'Aktion', value: (row) => row.action },
            { header: 'Objekt', value: (row) => `${row.objectType} ${row.objectLabel}`.trim() },
            { header: 'Ergebnis', value: (row) => row.result },
          ]),
        );
      },
    },
    workflows: {
      palette: (signal) => client.get<readonly PaletteEntry[]>('/workflows/palette', withSignal(signal)),
      graph: async (id, signal) => {
        try {
          return normalizeGraph(await client.get(`/workflows/${id}/graph`, withSignal(signal)));
        } catch (error) {
          // A process nobody has published has no graph yet: the editor starts empty.
          if (error instanceof ApiError && (error.status === 404 || error.status === 409)) return null;
          throw error;
        }
      },
      check: async (id, graph) =>
        (await client.post<{ problems: readonly { code: string; message: string }[] }>(`/workflows/${id}/graph/check`, graph))
          .problems,
      publish: (id, graph, note) =>
        client.post<{ version: number }>(`/workflows/${id}/graph/publish`, graph, {
          query: { note },
          idempotencyKey: true,
        }),
    },
    mfa: {
      begin: () =>
        client.post<{ methodId: string; secret: string; provisioningUri: string }>('/mfa/enrol', {
          label: 'Authenticator',
        }),
      confirm: (methodId, code) =>
        client.post<{ recoveryCodes: readonly string[] }>(`/mfa/methods/${methodId}/confirm`, { code }),
      disable: async () => {
        const methods = await client.get<
          readonly { readonly id: string; readonly confirmed_at?: string | null; readonly disabled_at?: string | null }[]
        >('/mfa/methods');
        const active = methods.filter((method) => method.confirmed_at && !method.disabled_at);
        await Promise.all(active.map((method) => client.delete<void>(`/mfa/methods/${method.id}`)));
      },
    },
    navigation: {
      get: async (signal) =>
        navigationPreferencesFromWire(
          await client.get<NavigationPreferencesWire>('/me/navigation', withSignal(signal)),
        ),
      save: async (preferences) => {
        const wire = await client.put<NavigationPreferencesWire | undefined>('/me/navigation', {
          order: preferences.order,
          hidden: preferences.hidden,
        });
        // A server that confirms without a body did what was asked; what we sent is then the answer.
        return wire
          ? navigationPreferencesFromWire(wire)
          : { ...preferences, updatedAt: null };
      },
      reset: () => client.delete<void>('/me/navigation'),
    },
    settings: {
      security: (signal) => client.get<SecuritySettings>('/settings/security', withSignal(signal)),
      revokeSession: (id) =>
        client.post<void>(`/settings/security/sessions/${id}/revoke`, undefined, {
          idempotencyKey: true,
        }),
      changePassword: (payload) =>
        client.post<void>('/settings/security/password', payload, { idempotencyKey: true }),
      accessibility: (signal) =>
        client.get<AccessibilityPreferences>('/settings/accessibility', withSignal(signal)),
      saveAccessibility: (preferences) =>
        client.put<AccessibilityPreferences>('/settings/accessibility', preferences),
      dateMarkers: {
        list: async (signal) => {
          const wire = await client.get<DateMarkerListWire>(
            '/settings/date-markers',
            withSignal(signal),
          );
          const items = (wire.items ?? []).map(dateMarkerFromWire);
          // The server sends its own count; items.length keeps the screen correct if an older backend omits it.
          return { items, count: wire.count ?? items.length };
        },
        create: async (payload) => {
          const created = await client.post<DateMarkerWire>('/settings/date-markers', payload, {
            idempotencyKey: true,
          });
          return dateMarkerFromWire(created);
        },
        update: async (id, patch) => {
          const updated = await client.patch<DateMarkerWire>(
            `/settings/date-markers/${encodeURIComponent(id)}`,
            patch,
          );
          return dateMarkerFromWire(updated);
        },
        remove: async (id) => {
          await client.delete<void>(`/settings/date-markers/${encodeURIComponent(id)}`);
        },
        // The colon is the contract, not a typo: the endpoint is `…/date-markers:reset`, a command on the
        // collection rather than a member of it.
        reset: () => client.post<{ readonly deleted: number }>('/settings/date-markers:reset'),
      },
      kontoMapping: {
        list: async (query, signal) => {
          const wire = await client.get<KontoMappingListWire>('/settings/konto-mapping', {
            query: {
              mandantId: query?.mandantId,
              supplier: query?.supplier,
              category: query?.category,
            },
            ...withSignal(signal),
          });
          const items = (wire.items ?? []).map(kontoMappingFromWire);
          return {
            items,
            count: wire.count ?? items.length,
            // An older server has no categories; the dialog must still offer the taxonomy's values.
            categories:
              wire.categories && wire.categories.length > 0
                ? wire.categories
                : FALLBACK_KONTO_CATEGORIES,
          };
        },
        create: async (payload) =>
          kontoMappingFromWire(
            await client.post<KontoMappingWire>('/settings/konto-mapping', payload, {
              idempotencyKey: true,
            }),
          ),
        update: async (id, payload) =>
          kontoMappingFromWire(
            await client.patch<KontoMappingWire>(
              `/settings/konto-mapping/${encodeURIComponent(id)}`,
              payload,
            ),
          ),
        remove: async (id) => {
          await client.delete<void>(`/settings/konto-mapping/${encodeURIComponent(id)}`);
        },
        importCsv: async (file) => {
          // A multipart body, not JSON: the request goes through the same CSRF-aware client, which reads a
          // FormData body as it is instead of serialising it.
          const form = new FormData();
          form.append('file', file, file.name);
          const wire = await client.post<KontoMappingImportWire>(
            '/settings/konto-mapping/import',
            form,
            { idempotencyKey: true },
          );
          return {
            created: wire.created ?? 0,
            updated: wire.updated ?? 0,
            skipped: (wire.skipped ?? []).map((row) => ({
              line: row.line ?? 0,
              reason: row.reason ?? '',
            })),
          };
        },
      },
      mandantRules: {
        list: async (signal) => {
          const wire = await client.get<MandantRuleListWire>('/settings/mandant-rules', withSignal(signal));
          const items = (wire.items ?? []).map(mandantRuleFromWire);
          return { items, count: wire.count ?? items.length };
        },
        remove: async (id) => {
          await client.delete<void>(`/settings/mandant-rules/${encodeURIComponent(id)}`);
        },
      },
    },
  };
}
