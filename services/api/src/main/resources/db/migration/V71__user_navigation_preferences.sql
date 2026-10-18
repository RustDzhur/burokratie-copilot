-- =============================================================================
--  Persönliche Reihenfolge der Seitenleiste / per-person sidebar settings
-- =============================================================================
--  Die Seitenleiste ist für alle gleich, aber nicht jeder arbeitet gleich: wer
--  täglich Rechnungen prüft, will „Finanzen“ oben; wer Integrationen nie öffnet,
--  will sie nicht sehen. Bis hierher gab es dafür keinen Ort. Eine Einstellung
--  des ganzen Kontos wäre der falsche gewesen: sie hätte allen anderen die
--  Reihenfolge verändert.
--
--  Die Zeile gehört deshalb einer Person (user_id ist der Primärschlüssel). Sie
--  trägt trotzdem tenant_id, weil der Zugriff über das Konto läuft und die Row
--  Level Security (spec §3.2) die zweite Verteidigungslinie neben der
--  serverseitigen Autorisierung ist.
--
--  preference ist die Sicht des Clients, unverändert gespeichert:
--      {"version":1,"order":["dashboard","documents"],
--       "hidden":["integrations","accessibilitySettings"]}
--
--  Eine Liste bekannter Bildschirme gibt es hier absichtlich NICHT: der Katalog
--  lebt im Frontend, und ein neuer Bildschirm darf die gespeicherten
--  Einstellungen anderer Personen nicht ungültig machen. Unbekannte Kennungen
--  werden gespeichert, nicht abgelehnt.
-- =============================================================================

CREATE TABLE user_navigation_preferences (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    tenant_id  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    preference jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_navigation_preferences_object_chk
        CHECK (jsonb_typeof(preference) = 'object')
);

COMMENT ON TABLE user_navigation_preferences IS
    'Persönliche Reihenfolge und ausgeblendete Einträge der Seitenleiste; gehört einer Person, geprüft über tenant_id.';
COMMENT ON COLUMN user_navigation_preferences.preference IS
    '{"version":1,"order":[...],"hidden":[...]}; Kennungen werden nicht gegen einen Katalog geprüft.';

-- Der Primärschlüssel allein steht schon für die Suche nach der Person; der
-- zusätzliche Index trägt die Kontoprüfung der Policy und die Aufräumläufe beim
-- Löschen eines Kontos.
CREATE INDEX user_navigation_preferences_tenant_user_idx
    ON user_navigation_preferences (tenant_id, user_id);

ALTER TABLE user_navigation_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_navigation_preferences FORCE ROW LEVEL SECURITY;

-- Strenger als die Konvention aus V13 (die einen Systemkontext zulässt): Es gibt
-- keinen Wartungslauf, der fremde Menüeinstellungen lesen müsste. Ohne Konto-
-- Kontext ist die Tabelle deshalb leer, und nur die eigene Zeile ist sichtbar.
CREATE POLICY user_navigation_preferences_tenant_isolation
    ON user_navigation_preferences
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
