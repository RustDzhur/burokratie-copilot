-- =====================================================================================
-- V50 - one timeline over the event tables (task BM-04, decision D-05)
--
-- The events of a business already exist, each in the table of its module: case_events, contract_events, task_events,
-- workflow_events, message_delivery_events and the audit trail. The timeline is a view over them, not a copy: nothing is
-- written twice and nothing can disagree with its source.
--
-- event_time is when it happened in the world; recorded_at is when the platform learned of it. The two differ for a letter
-- that arrives late or a payment found in a statement, and a timeline that merges them hides the delay.
-- The view runs with the rights of the caller (security_invoker), so row level security of every source table applies.
-- =====================================================================================

CREATE VIEW v_business_timeline WITH (security_invoker = true) AS
SELECT e.tenant_id, 'CASE'::text AS source, e.id AS source_event_id, e.event_type, e.title, e.description,
       e.occurred_at AS event_time, e.created_at AS recorded_at, e.actor_user_id,
       coalesce(e.contact_id, c.counterparty_contact_id) AS contact_id, NULL::uuid AS contract_id, e.case_id,
       e.task_id, e.document_id
FROM case_events e JOIN cases c ON c.id = e.case_id
UNION ALL
SELECT e.tenant_id, 'CONTRACT', e.id, e.event_type, e.event_type, e.description,
       e.occurred_at, e.created_at, e.actor_user_id,
       k.counterparty_contact_id, e.contract_id, NULL::uuid, NULL::uuid, e.document_id
FROM contract_events e JOIN contracts k ON k.id = e.contract_id
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
       CASE a.entity_type WHEN 'contracts' THEN a.entity_id END,
       CASE a.entity_type WHEN 'cases' THEN a.entity_id END,
       NULL::uuid, NULL::uuid
FROM audit_events a
WHERE a.tenant_id IS NOT NULL AND a.entity_type IN ('contacts', 'contracts', 'cases') AND a.result = 'SUCCESS';

COMMENT ON VIEW v_business_timeline IS
    'Events of cases, contracts, tasks, workflows, messages and the audit trail in one list. event_time: when it happened. recorded_at: when the platform learned of it.';
