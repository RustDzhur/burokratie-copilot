/**
 * Approvals and the case workspace: how the rows of the backend (database column names) become what the screens use.
 */

import type { ApprovalRequestItem, CaseWorkspace, CompletionCriterion, PlanStep } from '../types';

interface ApprovalWire {
  readonly id: string;
  readonly title: string;
  readonly justification?: string | null;
  readonly subject_type: string;
  readonly approval_type: string;
  readonly required_role?: string | null;
  readonly requested_by_name?: string | null;
  readonly expires_at?: string | null;
  readonly created_at: string;
  readonly bound?: boolean;
  readonly case?: { readonly id: string; readonly reference: string; readonly title: string } | null;
  readonly material?: Readonly<Record<string, unknown>>;
  readonly warnings?: readonly string[];
  readonly mayDecide?: boolean;
}

export function approvalFromWire(wire: ApprovalWire): ApprovalRequestItem {
  return {
    id: wire.id,
    title: wire.title,
    justification: wire.justification ?? null,
    subjectType: wire.subject_type,
    approvalType: wire.approval_type,
    requiredRole: wire.required_role ?? null,
    requestedByName: wire.requested_by_name ?? null,
    expiresAt: wire.expires_at ?? null,
    createdAt: wire.created_at,
    bound: wire.bound === true,
    case: wire.case ?? null,
    material: wire.material ?? {},
    warnings: (wire.warnings ?? []) as ApprovalRequestItem['warnings'],
    mayDecide: wire.mayDecide === true,
  };
}

interface CriterionWire {
  readonly id: string;
  readonly kind: string;
  readonly description: string;
  readonly status: CompletionCriterion['status'];
  readonly met_at?: string | null;
  readonly waived_reason?: string | null;
}

interface StepWire {
  readonly step_key: string;
  readonly status: PlanStep['status'];
  readonly error_message?: string | null;
  readonly blockedBy?: readonly string[];
  readonly ready?: boolean;
  readonly input?: {
    readonly action?: string;
    readonly title?: string;
    readonly level?: number;
    readonly auto?: boolean;
    readonly dependsOn?: readonly string[];
    readonly evidenceRequired?: boolean;
  };
}

export function workspaceFromWire(
  workspace: { readonly goal?: string | null; readonly next_action?: string | null; readonly criteria?: readonly CriterionWire[] },
  plan: { readonly run?: { readonly status?: string } | null; readonly steps?: readonly StepWire[] } | null,
): CaseWorkspace {
  return {
    goal: workspace.goal ?? null,
    nextAction: workspace.next_action ?? null,
    criteria: (workspace.criteria ?? []).map((row) => ({
      id: row.id,
      kind: row.kind,
      description: row.description,
      status: row.status,
      metAt: row.met_at ?? null,
      waivedReason: row.waived_reason ?? null,
    })),
    plan: {
      status: plan?.run?.status ?? null,
      steps: (plan?.steps ?? []).map((step) => ({
        key: step.step_key,
        action: step.input?.action ?? '',
        title: step.input?.title ?? step.step_key,
        status: step.status,
        level: step.input?.level ?? 1,
        auto: step.input?.auto === true,
        dependsOn: step.input?.dependsOn ?? [],
        blockedBy: step.blockedBy ?? [],
        ready: step.ready === true,
        evidenceRequired: step.input?.evidenceRequired === true,
        errorMessage: step.error_message ?? null,
      })),
    },
  };
}
