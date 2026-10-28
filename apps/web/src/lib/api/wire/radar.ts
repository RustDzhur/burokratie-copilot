/**
 * Radar: how RadarController's rows (database column names) become what the screens use.
 */

import type { RadarEvent, RadarEvidence, RadarFinding, RadarFindingDetail, RadarSeverity, RadarStatus, RadarSummary } from '../types';

export interface RadarFindingWire {
  readonly id: string;
  readonly finding_type: string;
  readonly rule_id: string;
  readonly rule_version: number;
  readonly title: string;
  readonly description?: string | null;
  readonly status: RadarStatus;
  readonly severity: RadarSeverity;
  readonly confidence?: number | null;
  readonly detected_at: string;
  readonly last_evaluated_at: string;
  readonly related_case_id?: string | null;
  readonly related_contact_id?: string | null;
  readonly related_contract_id?: string | null;
  readonly assigned_to?: string | null;
  readonly snoozed_until?: string | null;
  readonly resolved_at?: string | null;
  readonly resolution_reason?: string | null;
  readonly explanation?: string | null;
  readonly reopen_count?: number | null;
  readonly evidence_count?: number | null;
  readonly reason_codes?: readonly string[] | null;
  readonly allowedStatuses?: readonly RadarStatus[] | null;
  readonly evidence?: readonly {
    readonly id: string;
    readonly role: RadarEvidence['role'];
    readonly document_id?: string | null;
    readonly document_name?: string | null;
    readonly page?: number | null;
    readonly snippet?: string | null;
  }[];
  readonly history?: readonly {
    readonly id: string;
    readonly event_type: string;
    readonly from_status?: string | null;
    readonly to_status?: string | null;
    readonly reason?: string | null;
    readonly created_at: string;
  }[];
}

export function radarFindingFromWire(wire: RadarFindingWire): RadarFinding {
  return {
    id: wire.id,
    type: wire.finding_type,
    ruleId: wire.rule_id,
    ruleVersion: wire.rule_version,
    title: wire.title,
    description: wire.description ?? null,
    status: wire.status,
    severity: wire.severity,
    confidence: wire.confidence ?? null,
    detectedAt: wire.detected_at,
    lastEvaluatedAt: wire.last_evaluated_at,
    caseId: wire.related_case_id ?? null,
    contactId: wire.related_contact_id ?? null,
    contractId: wire.related_contract_id ?? null,
    assignedTo: wire.assigned_to ?? null,
    snoozedUntil: wire.snoozed_until ?? null,
    resolvedAt: wire.resolved_at ?? null,
    resolutionReason: wire.resolution_reason ?? null,
    explanation: wire.explanation ?? null,
    reopenCount: wire.reopen_count ?? 0,
    evidenceCount: wire.evidence_count ?? wire.evidence?.length ?? 0,
    reasonCodes: wire.reason_codes ?? [],
    allowedStatuses: wire.allowedStatuses ?? [],
  };
}

export function radarDetailFromWire(wire: RadarFindingWire): RadarFindingDetail {
  const evidence: RadarEvidence[] = (wire.evidence ?? []).map((row) => ({
    id: row.id,
    role: row.role,
    documentId: row.document_id ?? null,
    documentName: row.document_name ?? null,
    page: row.page ?? null,
    snippet: row.snippet ?? null,
  }));
  const history: RadarEvent[] = (wire.history ?? []).map((row) => ({
    id: row.id,
    eventType: row.event_type,
    fromStatus: row.from_status ?? null,
    toStatus: row.to_status ?? null,
    reason: row.reason ?? null,
    createdAt: row.created_at,
  }));
  return { ...radarFindingFromWire(wire), evidence, history };
}

export function radarSummaryFromWire(wire: {
  readonly byStatus?: Readonly<Record<string, number>>;
  readonly openBySeverity?: Readonly<Record<string, number>>;
  readonly recentlyResolved?: readonly { readonly id: string; readonly title: string; readonly severity: RadarSeverity; readonly resolved_at: string }[];
}): RadarSummary {
  return {
    byStatus: wire.byStatus ?? {},
    openBySeverity: wire.openBySeverity ?? {},
    recentlyResolved: (wire.recentlyResolved ?? []).map((row) => ({
      id: row.id,
      title: row.title,
      severity: row.severity,
      resolvedAt: row.resolved_at,
    })),
  };
}
