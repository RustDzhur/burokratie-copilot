/**
 * Invoices, bank transactions ("payments" on the screen) and contracts.
 */

import type {
  ContractStatus,
  ContractSummary,
  InvoiceDirection,
  InvoiceStatus,
  InvoiceSummary,
  PaymentStatus,
  PaymentSummary,
} from '../types';

/* ------------------------------ invoices ------------------------------ */

export interface InvoiceWire {
  readonly id: string;
  readonly invoiceNumber: string;
  readonly direction: string;
  readonly invoiceType?: string | null;
  readonly status: string;
  readonly currency: string;
  readonly grossAmount?: number | string | null;
  readonly issueDate?: string | null;
  readonly dueDate?: string | null;
  readonly supplierName?: string | null;
  readonly documentId?: string | null;
}

const INVOICE_STATUS_FROM_WIRE: Readonly<Record<string, InvoiceStatus>> = {
  DRAFT: 'draft',
  CANCELLED: 'draft',
  REJECTED: 'draft',
  RECEIVED: 'review',
  IN_REVIEW: 'review',
  APPROVED: 'approved',
  SCHEDULED: 'approved',
  PARTIALLY_PAID: 'approved',
  PAID: 'paid',
  CREDITED: 'paid',
  OVERDUE: 'overdue',
};

export function invoiceStatusesFor(status: string): string[] {
  return Object.entries(INVOICE_STATUS_FROM_WIRE)
    .filter(([, screen]) => screen === status)
    .map(([wire]) => wire);
}

export const directionToWire = (direction: InvoiceDirection): string =>
  direction === 'incoming' ? 'INCOMING' : 'OUTGOING';

export function invoiceFromWire(wire: InvoiceWire): InvoiceSummary {
  return {
    id: wire.id,
    number: wire.invoiceNumber,
    counterparty: wire.supplierName ?? '',
    direction: wire.direction === 'OUTGOING' ? 'outgoing' : 'incoming',
    status: INVOICE_STATUS_FROM_WIRE[wire.status] ?? 'review',
    amount: Number(wire.grossAmount ?? 0),
    currency: wire.currency,
    issueDate: wire.issueDate ?? null,
    dueDate: wire.dueDate ?? null,
    // A structured e-invoice is one that arrived with a document of its own; the validation result
    // (XRechnung/ZUGFeRD) is shown on the invoice card.
    eRechnung: wire.documentId !== null && wire.documentId !== undefined,
  };
}

/** The user types "1.234,50" or "1234.50"; the backend wants a plain decimal. */
export function decimalFromInput(input: string): string {
  const trimmed = input.trim().replace(/\s/g, '');
  if (trimmed.includes(',')) return trimmed.replace(/\./g, '').replace(',', '.');
  return trimmed;
}

/* ---------------------- bank transactions as payments ---------------------- */

export interface BankTransactionWire {
  readonly id: string;
  readonly booking_date: string;
  readonly amount: number | string;
  readonly currency: string;
  readonly counterparty_name?: string | null;
  readonly purpose?: string | null;
  readonly reference?: string | null;
  readonly status: string;
  readonly suggested_invoice?: string | null;
}

const PAYMENT_STATUS_FROM_WIRE: Readonly<Record<string, PaymentStatus>> = {
  UNMATCHED: 'unmatched',
  SUGGESTED: 'suggested',
  MATCHED: 'matched',
  IGNORED: 'ignored',
  INTERNAL: 'ignored',
};

export function paymentStatusesFor(status: string): string[] {
  return Object.entries(PAYMENT_STATUS_FROM_WIRE)
    .filter(([, screen]) => screen === status)
    .map(([wire]) => wire);
}

export function paymentFromWire(wire: BankTransactionWire): PaymentSummary {
  return {
    id: wire.id,
    bookingDate: wire.booking_date,
    counterparty: wire.counterparty_name ?? '',
    amount: Number(wire.amount),
    currency: wire.currency,
    reference: wire.reference ?? wire.purpose ?? null,
    status: PAYMENT_STATUS_FROM_WIRE[wire.status] ?? 'unmatched',
    suggestion: wire.suggested_invoice ?? null,
  };
}

/* ------------------------------ contracts ------------------------------ */

export interface ContractWire {
  readonly id: string;
  readonly title: string;
  readonly contractType?: string | null;
  readonly status: string;
  readonly startDate?: string | null;
  readonly endDate?: string | null;
  readonly noticePeriodValue?: number | null;
  readonly noticePeriodUnit?: string | null;
  readonly costAmount?: number | string | null;
  readonly costCurrency?: string | null;
  readonly partnerName?: string | null;
}

const CONTRACT_STATUS_FROM_WIRE: Readonly<Record<string, ContractStatus>> = {
  DRAFT: 'draft',
  ACTIVE: 'active',
  EXPIRING: 'expiring',
  EXPIRED: 'ended',
  TERMINATED: 'ended',
  ARCHIVED: 'ended',
};

/** `endDate` minus the notice period, or null when the unit is not one this can compute. */
export function noticeDeadline(
  endDate: string | null | undefined,
  value: number | null | undefined,
  unit: string | null | undefined,
): string | null {
  if (!endDate || !value || !unit) return null;
  const end = new Date(`${endDate}T00:00:00Z`);
  if (Number.isNaN(end.getTime())) return null;
  switch (unit.toUpperCase()) {
    case 'DAYS':
    case 'DAY':
      end.setUTCDate(end.getUTCDate() - value);
      break;
    case 'WEEKS':
    case 'WEEK':
      end.setUTCDate(end.getUTCDate() - value * 7);
      break;
    case 'MONTHS':
    case 'MONTH':
      end.setUTCMonth(end.getUTCMonth() - value);
      break;
    default:
      return null;
  }
  return end.toISOString().slice(0, 10);
}

export function contractFromWire(wire: ContractWire): ContractSummary {
  return {
    id: wire.id,
    partner: wire.partnerName ?? wire.title,
    contractType: wire.contractType ?? '',
    status: CONTRACT_STATUS_FROM_WIRE[wire.status] ?? 'active',
    startDate: wire.startDate ?? null,
    endDate: wire.endDate ?? null,
    noticePeriod:
      wire.noticePeriodValue && wire.noticePeriodUnit
        ? `${wire.noticePeriodValue} ${wire.noticePeriodUnit.toLowerCase()}`
        : null,
    noticeDeadline: noticeDeadline(wire.endDate, wire.noticePeriodValue, wire.noticePeriodUnit),
    value: wire.costAmount === null || wire.costAmount === undefined ? null : Number(wire.costAmount),
    currency: wire.costCurrency ?? null,
  };
}
