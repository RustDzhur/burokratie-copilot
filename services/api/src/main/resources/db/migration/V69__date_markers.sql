-- =============================================================================
--  Wortschatz für Datumsarten / date marker vocabulary  (spec §9.3)
-- =============================================================================
--  Bis hierher standen die Wörter, die eine Datumsangabe erklären, als reguläre
--  Ausdrücke in DateClassifier.java. Das hatte zwei Folgen:
--
--   1. Ein Wort, das dort nicht stand, existierte nicht. Bei tausend verschiedenen
--      Dokumenten ist jede feste Liste unvollständig, und der Nutzer konnte nichts
--      ergänzen.
--   2. §9.3 verlangt, dass die Taxonomie ohne Codeänderung pflegbar ist. Eine
--      Konstante in einer Klasse ist das Gegenteil davon.
--
--  Ab hier kommen die Wörter aus dieser Tabelle:
--    * tenant_id IS NULL  → Standard-Wortschatz, für alle Konten sichtbar,
--                           von keinem Konto änderbar (nur lesbar);
--    * tenant_id = X      → eigenes Wort oder Abschaltung eines Standardworts
--                           für genau dieses Konto.
--
--  Eine Abschaltung ist ein Eintrag mit enabled = false und demselben Wort:
--  der Abgleich nimmt für ein Konto das eigene Wort vor dem Standardwort.
--
--  phrase ist KLARTEXT, kein regulärer Ausdruck. Das ist Absicht: der Wortschatz
--  wird von Menschen im Einstellungsbereich gepflegt, und ein Muster, das ein
--  Nutzer versehentlich als Regex eingibt, wäre entweder wirkungslos oder eine
--  ReDoS-Falle. Der Abgleich erfolgt als Teilzeichenkette ohne Beachtung der
--  Groß-/Kleinschreibung, und entscheidend ist weiterhin die NÄHE zum Datum.
-- =============================================================================

CREATE TABLE date_markers (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid REFERENCES tenants (id) ON DELETE CASCADE,
    kind        varchar(32) NOT NULL,
    phrase      varchar(120) NOT NULL,
    label       varchar(200),
    enabled     boolean NOT NULL DEFAULT true,
    is_system   boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT date_markers_kind CHECK (kind IN (
        'PAYMENT_DEADLINE', 'OBJECTION_DEADLINE', 'DEADLINE',
        'APPOINTMENT', 'VALIDITY_END', 'DOCUMENT_DATE')),
    -- Ein Standardwort gehört niemandem; ein eigenes Wort gehört genau einem Konto.
    CONSTRAINT date_markers_system_has_no_tenant CHECK (
        (is_system AND tenant_id IS NULL) OR (NOT is_system AND tenant_id IS NOT NULL))
);

COMMENT ON TABLE date_markers IS
    'Wörter, die eine Datumsangabe erklären (Frist, Termin, Zahlung …). tenant_id NULL = Standard.';
COMMENT ON COLUMN date_markers.phrase IS
    'Klartext, keine Regex. Abgleich ohne Beachtung der Groß-/Kleinschreibung als Teilzeichenkette.';

-- Ein Konto darf dasselbe Wort nicht zweimal anlegen; für Standardwörter gilt dasselbe.
CREATE UNIQUE INDEX date_markers_global_unique
    ON date_markers (kind, lower(phrase)) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX date_markers_tenant_unique
    ON date_markers (tenant_id, kind, lower(phrase)) WHERE tenant_id IS NOT NULL;
CREATE INDEX date_markers_lookup ON date_markers (tenant_id, kind, enabled);

ALTER TABLE date_markers ENABLE ROW LEVEL SECURITY;
ALTER TABLE date_markers FORCE ROW LEVEL SECURITY;
CREATE POLICY date_markers_read ON date_markers FOR SELECT
    USING (tenant_id IS NULL OR tenant_id = current_tenant_id());
-- Schreiben nur im eigenen Konto. Der Standard gehört der Plattform: kein Konto
-- kann ihn ändern oder löschen, und das ist der Grund, warum INSERT hier ein
-- tenant_id verlangt.
--
-- Die Ausnahme für den Standard-Wortschatz ist nötig, weil V70 ihn ohne
-- Kontobindung einfügt: während der Migration ist kein app.tenant_id gesetzt,
-- also ist current_tenant_id() NULL, und ein Vergleich tenant_id = NULL ist nie
-- wahr. Ohne diesen Zweig scheitert V70 an der Policy. Er lässt nur echte
-- Standardzeilen zu, und nur solange kein Konto gebunden ist - ein normaler
-- Request hat immer ein Konto und kann deshalb keine Standardwörter anlegen.
CREATE POLICY date_markers_write ON date_markers FOR INSERT
    WITH CHECK (
        (tenant_id IS NULL AND is_system AND current_tenant_id() IS NULL)
        OR tenant_id = current_tenant_id());
CREATE POLICY date_markers_update ON date_markers FOR UPDATE
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY date_markers_delete ON date_markers FOR DELETE
    USING (tenant_id = current_tenant_id());
