-- =====================================================================================
-- V53 - what the Radar needs besides its tables (tasks RD-03, RD-04)
--
-- * notifications may carry the category RADAR;
-- * the thresholds of the first rules are settings, not constants in code. They are TECHNICAL DEFAULTS chosen so that the
--   Radar is neither silent nor noisy at the start; they are not legal or tax rules and are to be tuned with the measurements
--   of the evaluation set (task RD-09). Every tenant can override a value in its own settings.
-- =====================================================================================

ALTER TABLE notifications DROP CONSTRAINT notifications_category_chk;
ALTER TABLE notifications ADD CONSTRAINT notifications_category_chk CHECK (category IN (
    'TASK', 'DEADLINE', 'MISSED_DEADLINE', 'NEW_MAIL', 'DOCUMENT_REVIEW',
    'APPROVAL', 'INTEGRATION_ERROR', 'AUTOMATION_RESULT', 'EXTERNAL_RESPONSE',
    'CONTRACT_CHANGE', 'ARCHIVING', 'SECURITY', 'SYSTEM', 'RADAR'
));

INSERT INTO system_settings (tenant_id, setting_key, category, value, value_type, description, requires_verification) VALUES
(NULL, 'radar.enabled', 'OTHER', 'true', 'BOOLEAN',
 '{"de":"Radar eingeschaltet","en":"Radar enabled","ru":"Радар включён"}', false),
(NULL, 'radar.notify.min_severity', 'NOTIFICATIONS', '"MEDIUM"', 'STRING',
 '{"de":"Ab dieser Schwere meldet der Radar einen Befund (technischer Startwert)","en":"The Radar announces a finding from this severity (technical default)","ru":"С какой серьёзности Радар сообщает о находке (технический стартовый параметр)"}', false),
(NULL, 'radar.invoice.amount_change_percent', 'OTHER', '10', 'NUMBER',
 '{"de":"Abweichung des Rechnungsbetrags vom letzten Betrag in Prozent, ab der ein Befund entsteht (technischer Startwert)","en":"Deviation of an invoice amount from the previous amount, in percent, from which a finding is raised (technical default)","ru":"Отклонение суммы счёта от предыдущей, в процентах, с которого создаётся находка (технический стартовый параметр)"}', false),
(NULL, 'radar.invoice.amount_change_high_percent', 'OTHER', '50', 'NUMBER',
 '{"de":"Abweichung in Prozent, ab der der Befund als hoch gilt (technischer Startwert)","en":"Deviation in percent from which the finding is rated high (technical default)","ru":"Отклонение в процентах, с которого находка считается высокой (технический стартовый параметр)"}', false),
(NULL, 'radar.contract.notice_warning_days', 'OTHER', '60', 'NUMBER',
 '{"de":"Tage vor einer bestätigten Kündigungsfrist, ab denen der Radar warnt (technischer Startwert)","en":"Days before a confirmed notice deadline from which the Radar warns (technical default)","ru":"За сколько дней до подтверждённого срока уведомления Радар предупреждает (технический стартовый параметр)"}', false),
(NULL, 'radar.contract.notice_high_days', 'OTHER', '14', 'NUMBER',
 '{"de":"Tage vor einer bestätigten Kündigungsfrist, ab denen der Befund als hoch gilt (technischer Startwert)","en":"Days before a confirmed notice deadline from which the finding is rated high (technical default)","ru":"За сколько дней до срока находка считается высокой (технический стартовый параметр)"}', false),
(NULL, 'radar.case.missing_document_after_days', 'OTHER', '3', 'NUMBER',
 '{"de":"Tage nach Anlage eines Vorgangs, ab denen ein fehlendes Pflichtdokument gemeldet wird (technischer Startwert)","en":"Days after a case was opened from which a missing mandatory document is reported (technical default)","ru":"Через сколько дней после создания дела сообщается об отсутствующем обязательном документе (технический стартовый параметр)"}', false),
(NULL, 'radar.task.overdue_high_days', 'OTHER', '7', 'NUMBER',
 '{"de":"Tage Überfälligkeit einer Aufgabe, ab denen der Befund als hoch gilt (technischer Startwert)","en":"Days a task is overdue from which the finding is rated high (technical default)","ru":"Сколько дней задача просрочена, чтобы находка считалась высокой (технический стартовый параметр)"}', false)
ON CONFLICT DO NOTHING;
