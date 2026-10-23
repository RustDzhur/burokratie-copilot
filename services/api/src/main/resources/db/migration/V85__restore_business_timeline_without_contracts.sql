-- =============================================================================
--  V85 - the business timeline exists again, without the contracts module
-- =============================================================================
--  V50 built v_business_timeline over the event tables, including contract_events.
--  V76 dropped the contract tables with CASCADE, and that took the view with them:
--  the memory timeline (BM-04) then failed at runtime with "relation
--  v_business_timeline does not exist", a breakage no compiler can see because the
--  view is only a name in a SQL string.
--
--  This migration recreates the view over the sources that still exist. The
--  contract_id column stays so the timeline query keeps one shape for every
--  subject; it is always NULL now, because the product no longer has contracts.
--  The audit branch no longer maps entity_type 'contracts' for the same reason.
--
--  security_invoker stays on: the view runs with the caller's rights, so the row
--  level security of every source table still applies.
-- =============================================================================

drop view if exists v_business_timeline;

CREATE VIEW v_business_timeline WITH (security_invoker = true) AS
SELECT e.tenant_id, 'CASE'::text AS source, e.id AS source_event_id, e.event_type, e.title, e.description,
       e.occurred_at AS event_time, e.created_at AS recorded_at, e.actor_user_id,
       coalesce(e.contact_id, c.counterparty_contact_id) AS contact_id, NULL::uuid AS contract_id, e.case_id,
       e.task_id, e.document_id
FROM case_events e JOIN cases c ON c.id = e.case_id
UNION ALL
SELECT e.tenant_id, 'TASK', e.id, e.event_type, t.title, e.description,
       e.created_at, e.created_at, e.actor_user_id,
       c.counterparty_contact_id, NULL::uuid, t.case_id, e.task_id, NULL::uuid
FROM task_events e JOIN tasks t ON t.id = e.task_id LEFT JOIN cases c ON c.id = t.case_id
UNION ALL
SELECT e.tenant_id, 'WORKFLOW', e.id, e.event_type, coalesce(e.message, e.event_type), e.message,
       e.created_at, e.created_at, NULL::uuid,
       c.counterparty_contact_id, NULL::uuid, r.case_id, NULL::uuid, NULL::uuid
FROM workflow_events e JOIN workflow_runs r ON r.id = e.run_id LEFT JOIN cases c ON c.id = r.case_id
UNION ALL
SELECT e.tenant_id, 'MESSAGE', e.id, e.event_type, coalesce(m.subject, e.event_type), NULL::text,
       e.occurred_at, e.created_at, NULL::uuid,
       c.counterparty_contact_id, NULL::uuid, m.case_id, NULL::uuid, NULL::uuid
FROM message_delivery_events e JOIN outbound_messages m ON m.id = e.outbound_message_id LEFT JOIN cases c ON c.id = m.case_id
UNION ALL
SELECT a.tenant_id, 'AUDIT', a.id, a.action, coalesce(a.entity_label, a.action), a.description,
       a.created_at, a.created_at, a.actor_user_id,
       CASE a.entity_type WHEN 'contacts' THEN a.entity_id END,
       NULL::uuid,
       CASE a.entity_type WHEN 'cases' THEN a.entity_id END,
       NULL::uuid, NULL::uuid
FROM audit_events a
WHERE a.tenant_id IS NOT NULL AND a.entity_type IN ('contacts', 'cases') AND a.result = 'SUCCESS';

COMMENT ON VIEW v_business_timeline IS
    'Events of cases, tasks, workflows, messages and the audit trail in one list. event_time: when it happened. recorded_at: when the platform learned of it. contract_id is kept as NULL: the product no longer has contracts.';
