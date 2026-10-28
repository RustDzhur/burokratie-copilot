-- =====================================================================================
-- V88 — Agenten-Schritte in Prozessen, Warten auf eine Person, workflows.run.
--
-- A process could be drawn, published and run, but there was no step type that calls a
-- catalogue agent: a constellation could be shown as a graph and never executed. This
-- migration widens the two constraints the new behaviour needs:
--
--   * workflow_run_steps.step_type accepts AGENT (the engine calls AgentRunner),
--   * workflow_runs.status accepts PAUSED_FOR_HUMAN_REVIEW (the run stops on the failed
--     agent step and waits for APPROVE / RETRY / SKIP instead of silently continuing).
--
-- The permission is separate from workflows.execute on purpose: starting a process from a
-- template is an operational act, and OWNER / ADMIN / MANAGER are the roles that perform it.
-- =====================================================================================

-- The status column was varchar(20); PAUSED_FOR_HUMAN_REVIEW is 23 characters. Widening the
-- column before the constraint keeps a paused run from failing on the write that creates it.
ALTER TABLE workflow_runs ALTER COLUMN status TYPE varchar(40);
ALTER TABLE workflow_runs DROP CONSTRAINT workflow_runs_status_chk;
ALTER TABLE workflow_runs ADD CONSTRAINT workflow_runs_status_chk CHECK (status IN (
    'PENDING', 'RUNNING', 'WAITING', 'WAITING_APPROVAL', 'PAUSED_FOR_HUMAN_REVIEW',
    'COMPLETED', 'FAILED', 'CANCELLED', 'COMPENSATED'
));

ALTER TABLE workflow_run_steps DROP CONSTRAINT workflow_run_steps_type_chk;
ALTER TABLE workflow_run_steps ADD CONSTRAINT workflow_run_steps_type_chk CHECK (step_type IN (
    'ACTION', 'CONDITION', 'WAIT', 'DELAY', 'PARALLEL', 'APPROVAL',
    'NOTIFICATION', 'TASK_CREATE', 'INTEGRATION_CALL', 'SUB_WORKFLOW', 'AGENT', 'TERMINATE'
));

-- A paused run is found by an operator, not by its history: the partial index keeps the
-- "what waits for me" query small however many runs a tenant has completed.
CREATE INDEX workflow_runs_paused_idx
    ON workflow_runs (tenant_id, updated_at DESC)
    WHERE status = 'PAUSED_FOR_HUMAN_REVIEW';

INSERT INTO permissions (code, area, action, name) VALUES
('workflows.run','workflows','run','{"de":"Prozesse ausführen","en":"Run workflows","ru":"Запуск процессов"}')
ON CONFLICT (code) DO NOTHING;

-- V12 granted the existing workflow permissions to the roles; a permission created later
-- has to be granted explicitly, including to OWNER/ADMIN (whose "everything" rule ran in V12).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'workflows.run'
WHERE r.tenant_id IS NULL AND r.code IN ('OWNER', 'ADMIN', 'MANAGER')
ON CONFLICT DO NOTHING;
