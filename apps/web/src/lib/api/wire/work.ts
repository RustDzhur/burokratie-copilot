/**
 * Cases and tasks: CaseController / TaskController records and how the screens see them.
 */

import type { CaseStatus, CaseSummary, TaskPriority, TaskStatus, TaskSummary } from '../types';

export interface CaseWire {
  readonly id: string;
  readonly reference: string;
  readonly title: string;
  readonly status: string;
  readonly priority?: string | null;
  readonly typeCode?: string | null;
  readonly typeName?: string | null;
  readonly dueAt?: string | null;
  readonly documentCount?: number | null;
  readonly updatedAt?: string | null;
  readonly openedAt?: string | null;
  readonly responsibleName?: string | null;
}

export interface CaseDetailWire {
  readonly caseRecord: CaseWire;
  readonly requiredDocuments?: readonly string[] | null;
}

export interface TaskWire {
  readonly id: string;
  readonly title: string;
  readonly status: string;
  readonly priority: string;
  readonly dueAt?: string | null;
  readonly assigneeNames?: readonly string[] | null;
  readonly caseReference?: string | null;
}

const CASE_STATUS_FROM_WIRE: Readonly<Record<string, CaseStatus>> = {
  NEW: 'open',
  REOPENED: 'open',
  IN_PROGRESS: 'in_progress',
  WAITING_FOR_USER: 'waiting',
  WAITING_FOR_EXTERNAL_PARTY: 'waiting',
  WAITING_FOR_DOCUMENTS: 'waiting',
  WAITING_FOR_APPROVAL: 'waiting',
  ON_HOLD: 'waiting',
  COMPLETED: 'closed',
  CLOSED: 'closed',
};

const TASK_STATUS_FROM_WIRE: Readonly<Record<string, TaskStatus>> = {
  NEW: 'todo',
  IN_PROGRESS: 'doing',
  WAITING: 'doing',
  IN_REVIEW: 'doing',
  BLOCKED: 'blocked',
  DONE: 'done',
  CANCELLED: 'done',
};

const PRIORITY_FROM_WIRE: Readonly<Record<string, TaskPriority>> = {
  CRITICAL: 'urgent',
  HIGH: 'high',
  NORMAL: 'normal',
  LOW: 'low',
  INFORMATIONAL: 'low',
};

function wireKeysFor<T>(table: Readonly<Record<string, T>>, screenValue: string): string[] {
  return Object.entries(table)
    .filter(([, value]) => value === screenValue)
    .map(([key]) => key);
}

export const caseStatusesFor = (status: string): string[] => wireKeysFor(CASE_STATUS_FROM_WIRE, status);
export const taskStatusesFor = (status: string): string[] => wireKeysFor(TASK_STATUS_FROM_WIRE, status);
export const prioritiesFor = (priority: string): string[] => wireKeysFor(PRIORITY_FROM_WIRE, priority);

export const priorityToWire = (priority: TaskPriority): string =>
  ({ urgent: 'CRITICAL', high: 'HIGH', normal: 'NORMAL', low: 'LOW' })[priority];

/** A task status the status endpoint accepts. Completion has its own endpoint and is not listed. */
export const taskStatusToWire = (status: Exclude<TaskStatus, 'done'>): string =>
  ({ todo: 'NEW', doing: 'IN_PROGRESS', blocked: 'BLOCKED' })[status];

/** `YYYY-MM-DD` from a form date input to the instant the backend parses; local noon avoids day shifts. */
export function dueInstant(date: string | undefined): string | undefined {
  return date ? `${date}T12:00:00Z` : undefined;
}

const dateOnly = (instant?: string | null): string | null => (instant ? instant.slice(0, 10) : null);

export function caseFromWire(wire: CaseWire, missingDocuments?: boolean): CaseSummary {
  return {
    id: wire.id,
    reference: wire.reference,
    title: wire.title,
    status: CASE_STATUS_FROM_WIRE[wire.status] ?? 'open',
    caseType: wire.typeName ?? wire.typeCode ?? null,
    documentCount: wire.documentCount ?? 0,
    updatedAt: wire.updatedAt ?? wire.openedAt ?? '',
    nextDeadline: dateOnly(wire.dueAt),
    responsibleName: wire.responsibleName ?? null,
    ...(missingDocuments === undefined ? {} : { missingDocuments }),
  };
}

export function caseDetailFromWire(wire: CaseDetailWire): CaseSummary {
  return caseFromWire(wire.caseRecord, (wire.requiredDocuments ?? []).length > 0);
}

export function taskFromWire(wire: TaskWire, now: Date = new Date()): TaskSummary {
  const status = TASK_STATUS_FROM_WIRE[wire.status] ?? 'todo';
  return {
    id: wire.id,
    title: wire.title,
    status,
    priority: PRIORITY_FROM_WIRE[wire.priority] ?? 'normal',
    dueDate: dateOnly(wire.dueAt),
    assigneeName: wire.assigneeNames?.[0] ?? null,
    caseReference: wire.caseReference ?? null,
    overdue: status !== 'done' && !!wire.dueAt && new Date(wire.dueAt).getTime() < now.getTime(),
  };
}

/**
 * A screen's sort key to the property the backend sorts by. A key the backend cannot sort by is dropped
 * instead of sent: the list then keeps its default order rather than failing.
 */
export function sortFor(
  mapping: Readonly<Record<string, string>>,
  sort: string | number | undefined,
): string | undefined {
  if (typeof sort !== 'string') return undefined;
  const [field = '', direction] = sort.split(',');
  const mapped = mapping[field];
  return mapped ? (direction ? `${mapped},${direction}` : mapped) : undefined;
}

export const TASK_SORT = { title: 'title', dueDate: 'dueAt' } as const;
export const CASE_SORT = { title: 'title', updatedAt: 'updatedAt' } as const;
export const CONTRACT_SORT = { partner: 'title', endDate: 'endDate' } as const;
export const INVOICE_SORT = { number: 'invoiceNumber', dueDate: 'dueDate' } as const;
