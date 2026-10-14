-- =====================================================================================
-- V44 - the Telegram informer: which person gets the platform's news in which chat
--
-- One row per person of a workspace. The person asks for a link code on the integrations screen, opens the bot with it
-- (t.me/<bot>?start=<code>), and the bot stores the chat. `level` says how much news they want: ALL (every arriving
-- and processed document) or IMPORTANT (what needs a decision, deadlines, anything stopped).
-- =====================================================================================
CREATE TABLE telegram_links (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    link_code        varchar(40),
    code_expires_at  timestamptz,
    chat_id          bigint,
    linked_at        timestamptz,
    level            varchar(12) NOT NULL DEFAULT 'ALL',
    enabled          boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT telegram_links_level_chk CHECK (level IN ('ALL', 'IMPORTANT')),
    CONSTRAINT telegram_links_person_key UNIQUE (tenant_id, user_id)
);

CREATE UNIQUE INDEX telegram_links_code_key ON telegram_links (link_code) WHERE link_code IS NOT NULL;
CREATE INDEX telegram_links_chat_idx ON telegram_links (chat_id) WHERE chat_id IS NOT NULL;

CREATE TRIGGER telegram_links_set_updated_at BEFORE UPDATE ON telegram_links
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- A request sees its own workspace; the bot's poller (no workspace) must find a person by the code or the chat.
ALTER TABLE telegram_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE telegram_links FORCE ROW LEVEL SECURITY;
CREATE POLICY telegram_links_tenant_isolation ON telegram_links
    USING (current_tenant_id() IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (current_tenant_id() IS NULL OR tenant_id = current_tenant_id());

-- Where the poller stopped reading the bot's updates.
CREATE TABLE telegram_state (
    state_key    varchar(40) PRIMARY KEY,
    state_value  text NOT NULL
);
