-- =====================================================================================
-- V38 - take back the deadlines the platform invented
--
-- Until now a document that stated no deadline got "today + N days" (N from the scenario table) as an internal
-- deadline, and a document with an old date got "today + 7 days". An invoice from October 2025 whose term was
-- "5 days" therefore appeared as due on 28 October 2026: fourteen days after the day it was uploaded. Nobody
-- wrote that date; the platform made it up, and people upload their archive, not only fresh mail.
--
-- From now on a deadline comes from the document or does not exist. This migration removes the invented ones that
-- are already stored. A deadline read from a document (source DOCUMENT) is never touched, and neither is any date a
-- person entered.
-- =====================================================================================

WITH invented AS (
    DELETE FROM task_deadlines d
    WHERE d.source = 'WORKFLOW' AND d.deadline_type = 'INTERNAL'
      AND (d.note LIKE 'Das Dokument nennt keine Frist; Standardzeit%'
           OR d.note LIKE 'Das Dokument nennt %das liegt weit zurück%')
    RETURNING d.task_id, d.case_id
)
UPDATE tasks t SET due_at = NULL, remind_at = NULL
WHERE t.id IN (SELECT task_id FROM invented)
  AND t.source = 'DOCUMENT'
  AND NOT EXISTS (SELECT 1 FROM task_deadlines x WHERE x.task_id = t.id AND x.is_active);

-- The case date was copied from the same invented date.
UPDATE cases c SET due_at = NULL
WHERE c.due_at IS NOT NULL
  AND EXISTS (SELECT 1 FROM tasks t WHERE t.case_id = c.id AND t.source = 'DOCUMENT' AND t.due_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.case_id = c.id AND t.due_at IS NOT NULL AND t.deleted_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM task_deadlines x WHERE x.case_id = c.id AND x.is_active);

-- Calendar entries the platform wrote for contract dates that have already passed are history, not reminders.
-- This table's policy admits only rows of the current tenant and a migration has none, so the owner is let past it
-- for this one statement (FORCE is restored right after, inside the same migration transaction).
ALTER TABLE calendar_events NO FORCE ROW LEVEL SECURITY;
DELETE FROM calendar_events e
WHERE e.note IS NOT NULL AND e.source_document_id IS NOT NULL AND e.starts_on < CURRENT_DATE;
ALTER TABLE calendar_events FORCE ROW LEVEL SECURITY;
