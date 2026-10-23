-- =============================================================================
--  V87 - the demo's suppliers get their role, also in cabinets created before it existed
-- =============================================================================
--  The demo fixtures create two companies the office pays: the utility provider and the telephone company. New
--  demo sessions mark them as suppliers; sessions created before that flag was set have them with neither role,
--  and the client overview - which lists a contact unless it is a supplier - showed them as Mandanten.
--
--  The names are fixture names: they exist only in demo cabinets. A real office that happens to have a customer
--  called this way keeps the flag it set itself, because the update only marks contacts that are currently
--  unclassified.
-- =============================================================================

UPDATE contacts
   SET is_supplier = true
 WHERE display_name IN ('Stadtwerke Musterstadt GmbH', 'Beispiel Telekom GmbH')
   AND is_supplier = false
   AND is_customer = false;
