/**
 * Documents, review queue and document history: the backend's records and how the screens see them.
 *
 * The `…Wire` interfaces mirror the Java records one to one (DocumentController, ReviewController,
 * DocumentReviewController, AuditController), so a rename on the server is a type error here and not a
 * blank column in a table. Nothing outside `lib/api` knows these shapes.
 */

import type {
  AuditResult,
  DocumentChannel,
  DocumentDetail,
  DocumentHistoryEntry,
  DocumentStatus,
  DocumentSummary,
  ExtractedField,
  ReviewItem,
} from '../types';

export interface DocumentWire {
  readonly id: string;
  readonly originalFilename: string;
  readonly mimeType?: string | null;
  readonly sizeBytes?: number | null;
  readonly contentHash?: string | null;
  readonly processingStatus: string;
  readonly belegStatus?: string | null;
  readonly mandantId?: string | null;
  readonly reviewStatus?: string | null;
  readonly source: string;
  readonly documentTypeId?: string | null;
  readonly documentTypeCode?: string | null;
  readonly documentTypeNames?: Readonly<Record<string, string>> | null;
  readonly category?: string | null;
  readonly partyId?: string | null;
  readonly legalHold?: boolean;
  readonly receivedAt: string;
  readonly sender?: string | null;
  readonly senderDetails?: Readonly<Record<string, string>> | null;
  readonly senderContactId?: string | null;
  readonly senderSource?: string | null;
  readonly uploadedByName?: string | null;
  /** Extracted booking figures (Kanzlei edition, §5.1); omitted by the backend when not found. */
  readonly amount?: number | null;
  readonly amountCurrency?: string | null;
  readonly documentDate?: string | null;
  readonly supplierName?: string | null;
  readonly deadlineDate?: string | null;
  readonly deadlineDaysLeft?: number | null;
}

export interface UploadWire {
  readonly document: DocumentWire;
  readonly duplicate?: boolean;
}

export interface ExtractedValueWire {
  readonly fieldKey: string;
  readonly rawValue?: string | null;
  readonly normalizedValue?: string | null;
  readonly confidence?: number | null;
  readonly sourcePage?: number | null;
  readonly sourceSnippet?: string | null;
  readonly verified?: boolean;
}

export interface FieldsWire {
  readonly fields: readonly ExtractedValueWire[];
}

export interface ReviewQueueEntryWire {
  readonly reviewId: string;
  readonly documentId: string;
  readonly filename: string;
  readonly type: string;
  readonly reason?: string | null;
  readonly createdAt?: string | null;
}

export interface ReviewValueWire {
  readonly fieldKey: string;
  readonly value?: string | null;
  readonly normalizedValue?: string | null;
  readonly confidence?: number | null;
  readonly page?: number | null;
  readonly snippet?: string | null;
  readonly verified?: boolean;
}

export interface ReviewMatchWire {
  readonly matchType: string;
  readonly targetId: string;
  readonly status: string;
}

export interface ReviewDetailWire {
  readonly documentId: string;
  readonly filename: string;
  readonly openReviews: readonly { readonly id: string; readonly status: string }[];
  readonly values: readonly ReviewValueWire[];
  readonly matches: readonly ReviewMatchWire[];
}

export interface AuditEventWire {
  readonly eventId: string;
  readonly actorLabel?: string | null;
  readonly actorType?: string | null;
  readonly action: string;
  readonly result: string;
  readonly createdAt: string;
}

export interface AuditEventsWire {
  readonly events: readonly AuditEventWire[];
}

/* -------------------------------------------------------------------------- */
/* Status vocabulary                                                           */
/* -------------------------------------------------------------------------- */

const STATUS_FROM_WIRE: Readonly<Record<string, DocumentStatus>> = {
  RECEIVED: 'received',
  QUARANTINED: 'received',
  OCR_PENDING: 'processing',
  EXTRACTING: 'processing',
  CLASSIFYING: 'processing',
  MATCHING: 'processing',
  REVIEW_REQUIRED: 'needs_review',
  DUPLICATE: 'needs_review',
  READY: 'processed',
  ACTION_PENDING: 'processed',
  COMPLETED: 'processed',
  FAILED: 'failed',
  UNSUPPORTED: 'failed',
  CANCELLED: 'failed',
  ARCHIVED: 'archived',
  LEGAL_HOLD: 'archived',
};

/** The reverse of {@link STATUS_FROM_WIRE}: one screen status stands for several pipeline stages. */
export function wireStatusesFor(status: string): readonly string[] {
  return Object.entries(STATUS_FROM_WIRE)
    .filter(([, screen]) => screen === status)
    .map(([wire]) => wire);
}

const CHANNEL_FROM_WIRE: Readonly<Record<string, DocumentChannel>> = {
  UPLOAD: 'upload',
  EMAIL: 'email',
  SCANNER: 'scan',
  API: 'api',
  MOBILE: 'mobile',
  FOLDER_IMPORT: 'upload',
  GENERATED: 'api',
  MIGRATION: 'api',
};

export function wireSourcesFor(channel: string): readonly string[] {
  return Object.entries(CHANNEL_FROM_WIRE)
    .filter(([, screen]) => screen === channel)
    .map(([wire]) => wire);
}

/** A screen sort key to the entity property the backend sorts by. */
const SORT_FIELD_TO_WIRE: Readonly<Record<string, string>> = {
  title: 'originalFilename',
  receivedAt: 'receivedAt',
};

export function wireSort(sort: string | undefined): string | undefined {
  if (!sort) return undefined;
  const [field = '', direction] = sort.split(',');
  const mapped = SORT_FIELD_TO_WIRE[field];
  return mapped ? (direction ? `${mapped},${direction}` : mapped) : undefined;
}

/* -------------------------------------------------------------------------- */
/* Adapters                                                                    */
/* -------------------------------------------------------------------------- */

export function documentFromWire(wire: DocumentWire): DocumentSummary {
  return {
    id: wire.id,
    title: wire.originalFilename,
    documentType: wire.documentTypeCode ?? '',
    typeCode: wire.documentTypeCode ?? null,
    typeNames: wire.documentTypeNames ?? null,
    category: wire.category ?? null,
    partyId: wire.partyId ?? null,
    status: STATUS_FROM_WIRE[wire.processingStatus] ?? 'received',
    belegStatus: (wire.belegStatus as DocumentSummary['belegStatus']) ?? null,
    mandantId: wire.mandantId ?? null,
    channel: CHANNEL_FROM_WIRE[wire.source] ?? 'upload',
    senderName: wire.sender ?? null,
    sender: wire.sender || wire.senderContactId
      ? { ...(wire.senderDetails ?? {}), source: wire.senderSource ?? null }
      : null,
    senderContactId: wire.senderContactId ?? null,
    uploadedByName: wire.uploadedByName ?? null,
    receivedAt: wire.receivedAt,
    confidence: null,
    legalHold: wire.legalHold ?? false,
    sizeBytes: wire.sizeBytes ?? null,
    // Null, never a default: the screen shows a dash for a value the extraction did not find.
    amount: wire.amount ?? null,
    amountCurrency: wire.amountCurrency ?? null,
    documentDate: wire.documentDate ?? null,
    supplierName: wire.supplierName ?? null,
    deadlineDate: wire.deadlineDate ?? null,
    deadlineDaysLeft: wire.deadlineDaysLeft ?? null,
  };
}

function sourceHint(page?: number | null, snippet?: string | null): string | null {
  const parts = [page === null || page === undefined ? null : `p. ${page}`, snippet ? `“${snippet}”` : null].filter(Boolean);
  return parts.length > 0 ? parts.join(' · ') : null;
}

export function fieldFromWire(wire: ExtractedValueWire): ExtractedField {
  return {
    name: wire.fieldKey,
    label: wire.fieldKey,
    value: wire.normalizedValue ?? wire.rawValue ?? null,
    confidence: wire.confidence ?? 0,
    source: sourceHint(wire.sourcePage, wire.sourceSnippet),
    confirmed: wire.verified ?? false,
  };
}

function auditResultFromWire(result: string): AuditResult {
  if (result === 'SUCCESS') return 'success';
  if (result === 'DENIED') return 'denied';
  return 'failure';
}

export function historyFromWire(event: AuditEventWire): DocumentHistoryEntry {
  return {
    id: event.eventId,
    at: event.createdAt,
    actor: event.actorLabel ?? event.actorType ?? '',
    action: event.action,
    result: auditResultFromWire(event.result),
  };
}

const BROWSER_PREVIEW_TYPES: ReadonlySet<string> = new Set([
  'application/pdf',
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'text/plain',
]);

export function documentDetailFromWire(
  document: DocumentWire,
  fields: FieldsWire | null,
  history: AuditEventsWire | null,
): DocumentDetail {
  return {
    ...documentFromWire(document),
    uploadedBy: document.uploadedByName ?? (document.source === 'EMAIL' ? 'E-Mail' : null),
    checksum: document.contentHash ?? null,
    mimeType: document.mimeType ?? null,
    fields: (fields?.fields ?? []).map(fieldFromWire),
    history: (history?.events ?? []).map(historyFromWire),
    // GET /documents/{id}/content shows these types in the browser; everything else is only a download, and a
    // preview frame pointed at a download would fetch the file again on every visit.
    previewAvailable: BROWSER_PREVIEW_TYPES.has((document.mimeType ?? '').toLowerCase().split(';')[0]?.trim() ?? ''),
    processingError: document.processingStatus === 'FAILED' ? document.processingStatus : null,
  };
}

export function reviewItemFromWire(
  entry: ReviewQueueEntryWire,
  detail: ReviewDetailWire,
): ReviewItem {
  const duplicate = detail.matches.find((match) => match.matchType === 'DUPLICATE');
  return {
    documentId: entry.documentId,
    title: entry.filename,
    senderName: null,
    receivedAt: entry.createdAt ?? '',
    fields: detail.values.map((value) =>
      fieldFromWire({
        fieldKey: value.fieldKey,
        rawValue: value.value,
        normalizedValue: value.normalizedValue,
        confidence: value.confidence,
        sourcePage: value.page,
        sourceSnippet: value.snippet,
        verified: value.verified,
      }),
    ),
    possibleDuplicateOf: duplicate?.targetId ?? null,
  };
}

/** The decision body for the fields a reviewer confirmed (or corrected). */
export function approvalFromFields(
  fields: readonly { readonly name: string; readonly value: string }[],
  typeCode?: string | null,
): { decision: 'APPROVED'; changes: { fieldValues: Record<string, string>; documentTypeCode?: string } } {
  // ReviewService stores every value under `fieldValues` as verified, so a corrected value stays
  // distinguishable from an extracted one.
  return {
    decision: 'APPROVED',
    changes: {
      fieldValues: Object.fromEntries(fields.map((field) => [field.name, field.value])),
      ...(typeCode ? { documentTypeCode: typeCode } : {}),
    },
  };
}
