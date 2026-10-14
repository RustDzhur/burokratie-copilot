-- =====================================================================================
-- V51 - a task can answer an obligation (task BM-06, decision D-06)
--
-- A contract obligation (contract_obligations) has a due date and, sometimes, a responsible person. Until now nothing said
-- which task takes care of it, so an obligation could fall between the obligation list and the task list. tasks.obligation_id
-- is that link; the query "obligations nobody owns and no task serves" is then plain SQL.
-- =====================================================================================

ALTER TABLE tasks ADD COLUMN obligation_id uuid REFERENCES contract_obligations (id) ON DELETE SET NULL;
CREATE INDEX tasks_obligation_idx ON tasks (obligation_id) WHERE obligation_id IS NOT NULL AND deleted_at IS NULL;
