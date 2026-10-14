/**
 * What the platform did with a document, as `GET /documents/{id}/pipeline` reports it.
 *
 * The shape is the backend's own and is read as it is: the outcome sentence, the next step and the step list
 * come from what was stored, so the screen never has to guess a state from a status label.
 */
export interface ProcessingStep {
  readonly step: string;
  readonly status: string;
  readonly attempt: number;
  readonly startedAt: string | null;
  readonly finishedAt: string | null;
  readonly error: string | null;
}

export type ProcessingResult =
  | 'PROCESSING'
  | 'WAITING_FOR_REVIEW'
  | 'TASK_CREATED'
  | 'FILE_ONLY'
  | 'DUPLICATE'
  | 'FAILED'
  | 'SKIPPED'
  | string;

export interface ProcessingOutcome {
  readonly result: ProcessingResult;
  readonly explanation: string | null;
  readonly next: string;
  readonly taskId?: string;
  readonly taskTitle?: string;
  readonly taskStatus?: string;
  readonly priority?: string;
  readonly dueAt?: string | null;
  readonly caseId?: string | null;
  readonly contractId?: string;
  readonly assignmentPending?: boolean;
}

export interface SuggestedType {
  readonly code: string;
  readonly name: string;
  readonly confidence: number | null;
  readonly rationale: string | null;
}

export interface ProcessingFlow {
  readonly documentId: string;
  readonly processingStatus: string;
  readonly reviewStatus: string | null;
  readonly documentType: string | null;
  readonly documentTypeName: string | null;
  readonly confidence: number | null;
  readonly suggestedType: SuggestedType | null;
  readonly reviewReason: string | null;
  readonly textPreview: string | null;
  readonly steps: readonly ProcessingStep[];
  readonly outcome: ProcessingOutcome;
}
