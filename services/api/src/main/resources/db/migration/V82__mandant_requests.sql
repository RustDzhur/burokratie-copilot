-- =============================================================================
--  V82 - Anfragen an Mandanten: welche Belege noch fehlen
--        (Kanzlei edition, §5.7 "Anfragen / Belegebeschaffung")
-- =============================================================================
--  §5.7 asks for a letter from the voucher card that asks the client for what
--  is missing, a history on the client card, and a reminder after seven days.
--  The platform had mail and templates, but nothing that remembered a request:
--  the letter left the house and the only trace was a sent message nobody could
--  connect to the voucher it was about.
--
--  This table is that trace. It stores the subject and the body that actually
--  went out, not a template reference: editing the template later must not
--  rewrite the history of what a client was asked for.
--
--  A request is not a task and not a deadline. It has its own small lifecycle
--  (DRAFT -> SENT -> ANSWERED | OVERDUE, and CANCELLED from any open state),
--  because "asked the client" and "the answer arrived" are the two facts the
--  office needs, and neither is a task status.
-- =============================================================================

CREATE TABLE mandant_requests (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    -- The client the request goes to. A deleted client takes the request with
    -- it: a letter without a recipient is not a history, it is a loose end.
    mandant_id       uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    -- The voucher the request is about. Nullable because a bookkeeper may ask
    -- for records without naming one voucher ("Belege für März", for example).
    document_id      uuid REFERENCES documents (id) ON DELETE SET NULL,
    created_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    -- The text as it was written/sent. Stored, not derived: the letter of
    -- 03.02.2026 must read the same after the template was changed.
    subject          text NOT NULL,
    body             text NOT NULL,
    -- DRAFT     written, not sent yet
    -- SENT      handed to the mail provider, no answer yet
    -- ANSWERED  an answer arrived (a person marked it, or the office did)
    -- OVERDUE   seven days passed without an answer
    -- CANCELLED the request was withdrawn
    status           varchar(20) NOT NULL DEFAULT 'DRAFT',
    sent_at          timestamptz,
    last_reminder_at timestamptz,
    reminder_count   integer NOT NULL DEFAULT 0,
    answered_at      timestamptz,
    closed_at        timestamptz,
    note             text,
    CONSTRAINT mandant_requests_status_chk CHECK (status IN (
        'DRAFT', 'SENT', 'ANSWERED', 'OVERDUE', 'CANCELLED'
    )),
    CONSTRAINT mandant_requests_subject_chk CHECK (length(btrim(subject)) > 0),
    CONSTRAINT mandant_requests_body_chk CHECK (length(btrim(body)) > 0),
    CONSTRAINT mandant_requests_reminder_count_chk CHECK (reminder_count >= 0)
);

COMMENT ON TABLE mandant_requests IS
    'Letters that ask a client for missing vouchers, with their history (Kanzlei edition, §5.7).';
COMMENT ON COLUMN mandant_requests.body IS
    'The letter as it was stored or sent. Never re-rendered from a template afterwards.';
COMMENT ON COLUMN mandant_requests.status IS
    'DRAFT, SENT, ANSWERED, OVERDUE or CANCELLED. OVERDUE is the state after the seven-day reminder.';
COMMENT ON COLUMN mandant_requests.reminder_count IS
    'How often the office was reminded that this request is unanswered.';
COMMENT ON COLUMN mandant_requests.closed_at IS
    'When the request left the open states: on an answer or on cancellation.';

-- The client card reads one client's requests newest first.
CREATE INDEX mandant_requests_tenant_mandant_created_idx
    ON mandant_requests (tenant_id, mandant_id, created_at DESC);

-- The reminder job looks for SENT requests whose seven days have passed.
CREATE INDEX mandant_requests_tenant_status_sent_idx
    ON mandant_requests (tenant_id, status, sent_at)
    WHERE status IN ('SENT', 'OVERDUE');

-- Row level security exactly as every other tenant table: the policy is the
-- second guard behind the tenant_id in each query. The service takes the
-- account from the session, never from the request. Requests are written in
-- the tenant of their session, so a row can never appear in two accounts.
ALTER TABLE mandant_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE mandant_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY mandant_requests_tenant_isolation ON mandant_requests
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
