/**
 * Contacts, calendar, mail, templates, automations and the assistant.
 */

import type {
  AssistantAnswer,
  AssistantToolAnswer,
  AutomationStatus,
  AutomationSummary,
  CalendarEvent,
  ContactSummary,
  ContactType,
  MailFolder,
  MailMessageSummary,
  MailStatus,
  TemplateSummary,
} from '../types';

/* -------------------------------- contacts -------------------------------- */

export interface ContactWire {
  readonly id: string;
  readonly display_name: string;
  readonly email?: string | null;
  readonly phone?: string | null;
  readonly updated_at: string;
  readonly kind: string;
  readonly city?: string | null;
}

const CONTACT_TYPE_FROM_WIRE: Readonly<Record<string, ContactType>> = {
  PERSON: 'person',
  ORGANISATION: 'organisation',
  AUTHORITY: 'authority',
};

export const contactKindToWire = (type: ContactType): string =>
  ({ person: 'PERSON', organisation: 'ORGANISATION', authority: 'AUTHORITY' })[type];

export function contactFromWire(wire: ContactWire): ContactSummary {
  return {
    id: wire.id,
    name: wire.display_name,
    contactType: CONTACT_TYPE_FROM_WIRE[wire.kind] ?? 'person',
    email: wire.email ?? null,
    phone: wire.phone ?? null,
    city: wire.city ?? null,
    updatedAt: wire.updated_at,
  };
}

/* -------------------------------- calendar -------------------------------- */

export interface CalendarEventWire {
  readonly id: string;
  readonly title: string;
  readonly date: string;
  readonly end_date?: string | null;
  readonly type: string;
  readonly all_day?: boolean;
  readonly case_reference?: string | null;
  readonly case_id?: string | null;
  readonly note?: string | null;
  readonly document_id?: string | null;
  readonly task_id?: string | null;
  readonly origin?: string | null;
}

const EVENT_ORIGIN_FROM_WIRE: Readonly<Record<string, NonNullable<CalendarEvent['origin']>>> = {
  CALENDAR_ENTRY: 'calendar-entry',
  TASK_DEADLINE: 'task-deadline',
  CASE_DUE: 'case-due',
};

const EVENT_TYPE_FROM_WIRE: Readonly<Record<string, CalendarEvent['type']>> = {
  DEADLINE: 'deadline',
  APPOINTMENT: 'appointment',
  REMINDER: 'reminder',
};

export function calendarEventFromWire(wire: CalendarEventWire): CalendarEvent {
  return {
    id: wire.id,
    title: wire.title,
    date: wire.date,
    endDate: wire.end_date ?? null,
    type: EVENT_TYPE_FROM_WIRE[wire.type] ?? 'appointment',
    caseReference: wire.case_reference ?? null,
    allDay: wire.all_day ?? true,
    note: wire.note ?? null,
    origin: wire.origin ? EVENT_ORIGIN_FROM_WIRE[wire.origin] : undefined,
    caseId: wire.case_id ?? null,
    documentId: wire.document_id ?? null,
    taskId: wire.task_id ?? null,
  };
}

/* ---------------------------------- mail ---------------------------------- */

export interface MailWire {
  readonly id: string;
  readonly subject: string;
  readonly sender: string;
  readonly folder: string;
  readonly status: string;
  readonly moment: string;
  readonly attachment_count: number;
}

const MAIL_FOLDER_FROM_WIRE: Readonly<Record<string, MailFolder>> = {
  INBOX: 'inbox',
  SENT: 'sent',
  DRAFTS: 'drafts',
};

const MAIL_STATUS_FROM_WIRE: Readonly<Record<string, MailStatus>> = {
  NEW: 'new',
  PROCESSED: 'processed',
  NEEDS_REPLY: 'needs_reply',
};

export const mailFolderToWire = (folder: string): string | undefined =>
  Object.entries(MAIL_FOLDER_FROM_WIRE).find(([, screen]) => screen === folder)?.[0];

export const mailStatusToWire = (status: string): string | undefined =>
  Object.entries(MAIL_STATUS_FROM_WIRE).find(([, screen]) => screen === status)?.[0];

export function mailFromWire(wire: MailWire): MailMessageSummary {
  return {
    id: wire.id,
    subject: wire.subject,
    from: wire.sender,
    folder: MAIL_FOLDER_FROM_WIRE[wire.folder] ?? 'inbox',
    status: MAIL_STATUS_FROM_WIRE[wire.status] ?? 'new',
    receivedAt: wire.moment,
    attachmentCount: wire.attachment_count,
  };
}

/* ------------------------------- templates ------------------------------- */

export interface TemplateWire {
  readonly id: string;
  readonly name: string;
  readonly code: string;
  readonly category: string;
  readonly status: string;
  readonly updated_at: string;
}

export function templateFromWire(wire: TemplateWire): TemplateSummary {
  return {
    id: wire.id,
    name: wire.name,
    templateType: wire.category,
    status: wire.status === 'PUBLISHED' ? 'published' : 'draft',
    updatedAt: wire.updated_at,
  };
}

const TEMPLATE_CATEGORIES = [
  'LETTER',
  'APPLICATION',
  'OBJECTION',
  'TERMINATION',
  'REMINDER',
  'COMPLAINT',
  'INVOICE',
  'CONTRACT',
  'INTERNAL',
];

/** A free-text template type ("Kündigung", "letter") to the backend's closed category list. */
export function templateCategoryToWire(type: string): string {
  const upper = type.trim().toUpperCase().replace(/[\s-]+/g, '_');
  return TEMPLATE_CATEGORIES.includes(upper) ? upper : 'OTHER';
}

/**
 * The field catalogue of a CONTRACT template (V72), grouped by source. The list mirrors the backend's
 * {@code ContractTemplateValues} vocabulary; a placeholder whose source is not listed here resolves to a
 * hand-filled custom field.
 */
export const CONTRACT_TEMPLATE_FIELD_GROUPS: readonly {
  readonly source: string;
  readonly prefix: string;
  readonly fields: readonly string[];
}[] = [
  {
    source: 'VERTRAG',
    prefix: 'Vertrag',
    fields: [
      'Title', 'Nummer', 'Typ', 'Status', 'Beschreibung', 'Start', 'Ende', 'UnterzeichnetAm',
      'AutoVerlaengerung', 'VerlaengerungsMonate', 'Kuendigungsfrist', 'KuendigungBis', 'Betrag',
      'Waehrung', 'Zahlungsintervall', 'Laufzeit',
    ],
  },
  {
    source: 'PARTEI',
    prefix: 'Partei',
    fields: [
      'Name', 'Rolle', 'Anschrift', 'Email', 'Telefon', 'Gegenpartei.Name', 'Gegenpartei.Rolle',
      'Gegenpartei.Anschrift', 'Gegenpartei.Email', 'Gegenpartei.Telefon',
    ],
  },
  { source: 'WERK', prefix: 'Werk', fields: ['Name', 'Adresse'] },
  { source: 'KUNDE', prefix: 'Kunde', fields: ['Name', 'Organisation', 'Anschrift', 'Email', 'Telefon'] },
];

/** The source entity a placeholder draws from: contract sources by their prefix, everything else CUSTOM. */
export function templateSourceEntityFor(placeholder: string): string {
  const prefix = placeholder.split('.')[0]?.toLowerCase() ?? '';
  return { vertrag: 'VERTRAG', partei: 'PARTEI', werk: 'WERK', kunde: 'KUNDE' }[prefix] ?? 'CUSTOM';
}

/** The path within the source, so {@code Vertrag.Title} declares source VERTRAG and path Title. */
export function templateSourcePathFor(placeholder: string): string {
  const dot = placeholder.indexOf('.');
  return dot >= 0 ? placeholder.slice(dot + 1) : '';
}

/** `{{ key }}` placeholders found in a body, each once, in order of appearance. */
export function placeholdersIn(body: string): string[] {
  const found = new Set<string>();
  for (const match of body.matchAll(/\{\{\s*([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*)\s*}}/g)) {
    if (match[1]) found.add(match[1]);
  }
  return [...found];
}

/** A template code the backend accepts and that is unique enough to be typed twice without clashing. */
export function templateCode(name: string, now: number = Date.now()): string {
  const slug = name
    .normalize('NFKD')
    .replace(/[^\w\s-]/g, '')
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, '-')
    .slice(0, 50);
  return `${slug || 'vorlage'}-${now.toString(36)}`;
}

/* ------------------------------ automations ------------------------------ */

export interface WorkflowWire {
  readonly id: string;
  readonly code: string;
  readonly name?: string | null;
  readonly is_active: boolean;
  readonly current_version?: number | null;
  readonly trigger_type?: string | null;
  readonly last_run_at?: string | null;
  readonly last_run_status?: string | null;
  readonly runs_30d?: number | string | null;
}

export function automationFromWire(wire: WorkflowWire): AutomationSummary {
  // A process nobody has published a version of cannot run, whatever its active flag says.
  let status: AutomationStatus =
    (wire.current_version ?? 0) === 0 ? 'draft' : wire.is_active ? 'active' : 'paused';
  if (wire.last_run_status === 'FAILED') status = 'error';
  const lastRunResult =
    wire.last_run_status === 'FAILED'
      ? 'failed'
      : wire.last_run_status === 'COMPLETED'
        ? 'success'
        : wire.last_run_status
          ? 'running'
          : null;
  return {
    id: wire.id,
    name: wire.name ?? wire.code,
    trigger: wire.trigger_type ?? '',
    status,
    lastRunAt: wire.last_run_at ?? null,
    lastRunResult,
    runsLast30Days: Number(wire.runs_30d ?? 0),
  };
}

export const automationStatusMatches = (wire: WorkflowWire, wanted: string): boolean =>
  automationFromWire(wire).status === wanted;

/**
 * The backend files a process under how it starts (MANUAL, DOCUMENT, SCHEDULE, EVENT, API); the screen's
 * trigger names say what it reacts to. The screen's own name is kept in the trigger configuration.
 */
export function automationStartKind(trigger: string): string {
  switch (trigger) {
    case 'documentReceived':
    case 'invoiceReceived':
      return 'DOCUMENT';
    case 'schedule':
      return 'SCHEDULE';
    case 'mailReceived':
    case 'deadlineApproaching':
      return 'EVENT';
    default:
      return 'MANUAL';
  }
}

/** A process code the backend accepts (`^[a-z0-9_-]+$`). */
export function automationCode(name: string, now: number = Date.now()): string {
  const slug = name
    .normalize('NFKD')
    .replace(/[^\w\s-]/g, '')
    .trim()
    .toLowerCase()
    .replace(/[\s]+/g, '-')
    .slice(0, 50);
  return `${slug || 'ablauf'}-${now.toString(36)}`;
}

/* ------------------------------- assistant ------------------------------- */

export interface AssistantWire {
  readonly answer: string;
  readonly sources: readonly { readonly documentId: string; readonly title?: string | null; readonly page?: number | null }[];
  readonly model: string;
  readonly correlationId?: string | null;
  readonly disclaimerRequired?: boolean;
}

export function assistantAnswerFromWire(wire: AssistantWire): AssistantAnswer {
  return {
    answer: wire.answer,
    sources: wire.sources.map((source) => ({
      documentId: source.documentId,
      title: source.title ?? '',
      page: source.page ?? null,
    })),
    model: wire.model,
    correlationId: wire.correlationId ?? null,
    disclaimerRequired: wire.disclaimerRequired ?? false,
  };
}

/** The tool answer of the assistant: an answer, a proposal to confirm, or a refusal. */
export interface AssistantToolWire {
  readonly kind?: string | null;
  readonly message?: string | null;
  readonly proposalId?: string | null;
  readonly tool?: string | null;
  readonly preview?: string | null;
  readonly result?: Record<string, unknown> | null;
  readonly code?: string | null;
}

export function assistantToolAnswerFromWire(wire: AssistantToolWire): AssistantToolAnswer {
  const kind = wire.kind === 'PROPOSAL' || wire.kind === 'REFUSED' || wire.kind === 'ANSWER' ? wire.kind : 'ANSWER';
  return {
    kind,
    message: wire.message ?? '',
    proposalId: wire.proposalId ?? null,
    tool: wire.tool ?? null,
    preview: wire.preview ?? null,
    result: wire.result ?? null,
    code: wire.code ?? null,
  };
}
