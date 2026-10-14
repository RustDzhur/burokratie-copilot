package de.buerokratiecopilot.telegram;

import java.util.Map;

/** Everything the informer says, in the three languages of the platform; a person gets the language of their profile. */
final class TelegramTexts {

    private static final Map<String, String[]> TEXTS = Map.ofEntries(
            // {de, en, ru}
            Map.entry("linked", new String[] {
                    "✅ Verbunden. Ich informiere Sie hier über Ihre Dokumente: was ankommt, wie es verarbeitet wurde, was wichtig ist und wo es abgelegt ist.\n\n/status – Stand der Dinge\n/stop – Meldungen anhalten\n/help – Hilfe",
                    "✅ Connected. I will keep you informed here about your documents: what arrives, how it was processed, what matters and where it is filed.\n\n/status – where things stand\n/stop – pause messages\n/help – help",
                    "✅ Подключено. Я буду сообщать здесь о ваших документах: что пришло, как обработано, что важно и куда сложено.\n\n/status – текущее состояние\n/stop – приостановить сообщения\n/help – помощь"}),
            Map.entry("codeInvalid", new String[] {
                    "Der Code ist ungültig oder abgelaufen. Erzeugen Sie in der Plattform unter „Integrationen“ einen neuen.",
                    "The code is invalid or has expired. Create a new one in the platform under “Integrations”.",
                    "Код недействителен или устарел. Создайте новый на платформе в разделе «Интеграции»."}),
            Map.entry("notLinked", new String[] {
                    "Dieser Chat ist noch nicht verbunden. Öffnen Sie in der Plattform „Integrationen“ → Telegram und folgen Sie dem Link.",
                    "This chat is not connected yet. Open “Integrations” → Telegram in the platform and follow the link.",
                    "Этот чат ещё не подключён. Откройте на платформе «Интеграции» → Telegram и перейдите по ссылке."}),
            Map.entry("stopped", new String[] {
                    "⏸ Meldungen angehalten. Mit /start können Sie sie wieder einschalten.",
                    "⏸ Messages paused. Use /start to switch them on again.",
                    "⏸ Сообщения приостановлены. Включить снова: /start."}),
            Map.entry("resumed", new String[] {"▶️ Meldungen sind wieder an.", "▶️ Messages are on again.", "▶️ Сообщения снова включены."}),
            Map.entry("test", new String[] {
                    "🔔 Testmeldung: Die Verbindung zur Plattform funktioniert.",
                    "🔔 Test message: the connection to the platform works.",
                    "🔔 Тестовое сообщение: связь с платформой работает."}),
            Map.entry("status", new String[] {
                    "📊 Stand der Dinge\nOffene Aufgaben: %s\nÜberfällig: %s\nDokumente, die auf Sie warten: %s",
                    "📊 Where things stand\nOpen tasks: %s\nOverdue: %s\nDocuments waiting for you: %s",
                    "📊 Текущее состояние\nОткрытых задач: %s\nПросрочено: %s\nДокументов ждут вас: %s"}),
            Map.entry("received", new String[] {
                    "📥 Neues Dokument: %s\nQuelle: %s\nIch lese es jetzt.",
                    "📥 New document: %s\nSource: %s\nI am reading it now.",
                    "📥 Новый документ: %s\nИсточник: %s\nЧитаю его сейчас."}),
            Map.entry("source.UPLOAD", new String[] {"Upload", "Upload", "Загрузка"}),
            Map.entry("source.EMAIL", new String[] {"E-Mail", "E-mail", "Почта"}),
            Map.entry("source.SCAN", new String[] {"Scan", "Scan", "Скан"}),
            Map.entry("type", new String[] {"Art: %s", "Type: %s", "Тип: %s"}),
            Map.entry("typeUnknown", new String[] {"Art: nicht erkannt", "Type: not recognised", "Тип: не определён"}),
            Map.entry("addressee", new String[] {"Betrifft: %s", "For: %s", "Кому: %s"}),
            Map.entry("importance", new String[] {"Wichtigkeit: %s", "Importance: %s", "Важность: %s"}),
            Map.entry("priority.CRITICAL", new String[] {"🔴 kritisch", "🔴 critical", "🔴 критично"}),
            Map.entry("priority.HIGH", new String[] {"🟠 hoch", "🟠 high", "🟠 высокая"}),
            Map.entry("priority.NORMAL", new String[] {"🟡 normal", "🟡 normal", "🟡 обычная"}),
            Map.entry("priority.LOW", new String[] {"🟢 niedrig", "🟢 low", "🟢 низкая"}),
            Map.entry("priority.INFORMATIONAL", new String[] {"⚪ nur zur Information", "⚪ for information only", "⚪ только к сведению"}),
            Map.entry("done.TASK_CREATED", new String[] {"Aufgabe angelegt: %s", "Task created: %s", "Создана задача: %s"}),
            Map.entry("done.FILE_ONLY", new String[] {"Nur abgelegt – es ist nichts zu tun.", "Only filed – nothing to do.", "Только сохранён – делать ничего не нужно."}),
            Map.entry("done.ARCHIVED_OLD", new String[] {"Als älteres Dokument still abgelegt (keine Aufgabe, keine Frist erfunden).", "Filed quietly as an older document (no task, no invented deadline).", "Тихо сохранён как старый документ (без задачи, срок не придуман)."}),
            Map.entry("done.WAITING", new String[] {"Wartet auf Ihre Entscheidung.", "Waiting for your decision.", "Ждёт вашего решения."}),
            Map.entry("due", new String[] {"Frist: %s", "Deadline: %s", "Срок: %s"}),
            Map.entry("amount", new String[] {"Betrag: %s €", "Amount: €%s", "Сумма: %s €"}),
            Map.entry("filed", new String[] {"Abgelegt unter: %s", "Filed under: %s", "Сложено в: %s"}),
            Map.entry("case", new String[] {"Vorgang: %s", "Case: %s", "Дело: %s"}),
            Map.entry("open", new String[] {"Öffnen: %s", "Open: %s", "Открыть: %s"}),
            Map.entry("reviewKind.NO_MATCH", new String[] {"Ich konnte die Art nicht bestimmen.", "I could not tell what kind it is.", "Не удалось определить тип."}),
            Map.entry("reviewKind.LOW_CONFIDENCE", new String[] {"Ich bin mir bei der Art nicht sicher genug (%s %%).", "I am not sure enough about the type (%s%%).", "Я недостаточно уверен в типе (%s %%)."}),
            Map.entry("reviewKind.TYPE_ALWAYS_REVIEW", new String[] {"Diese Art Dokument prüft immer ein Mensch.", "A person always checks this kind of document.", "Такой тип документов всегда проверяет человек."}),
            Map.entry("review", new String[] {"❓ Ein Dokument braucht Ihre Entscheidung: %s", "❓ A document needs your decision: %s", "❓ Документ ждёт вашего решения: %s"}),
            Map.entry("stoppedDoc", new String[] {"⚠️ Ein Dokument konnte nicht vollständig verarbeitet werden: %s", "⚠️ A document could not be fully processed: %s", "⚠️ Документ не удалось обработать полностью: %s"}),
            Map.entry("duplicate", new String[] {"♻️ Duplikat abgelegt: %s (startet keinen zweiten Vorgang).", "♻️ Duplicate filed: %s (starts no second case).", "♻️ Дубликат сохранён: %s (второй процесс не запускается)."}),
            Map.entry("overdue", new String[] {"⏰ Aufgabe überfällig: %s", "⏰ Task overdue: %s", "⏰ Задача просрочена: %s"}),
            Map.entry("reminder", new String[] {"🔔 Erinnerung: %s", "🔔 Reminder: %s", "🔔 Напоминание: %s"}),
            Map.entry("unassigned", new String[] {"👤 Aufgabe ohne Zuständigkeit: %s", "👤 Task without an owner: %s", "👤 Задача без ответственного: %s"}),
            Map.entry("category.FINANCE", new String[] {"Finanzen", "Finance", "Финансы"}),
            Map.entry("category.CONTRACTS", new String[] {"Verträge", "Contracts", "Договоры"}),
            Map.entry("category.GOVERNMENT", new String[] {"Behörden und Steuern", "Authorities and taxes", "Органы и налоги"}),
            Map.entry("category.HOUSING", new String[] {"Wohnen", "Housing", "Жильё"}),
            Map.entry("category.EMPLOYMENT", new String[] {"Arbeit", "Employment", "Работа"}),
            Map.entry("category.FAMILY_EDUCATION_HEALTH", new String[] {"Familie, Bildung, Gesundheit", "Family, education, health", "Семья, образование, здоровье"}),
            Map.entry("category.LEGAL", new String[] {"Recht", "Legal", "Право"}),
            Map.entry("category.OTHER", new String[] {"Sonstiges", "Other", "Прочее"}),
            Map.entry("category.UNSORTED", new String[] {"Nicht zugeordnet", "Unsorted", "Не рассортировано"}));

    private TelegramTexts() {
    }

    static int index(String locale) {
        if (locale == null) {
            return 0;
        }
        return switch (locale.toLowerCase(java.util.Locale.ROOT).substring(0, Math.min(2, locale.length()))) {
            case "en" -> 1;
            case "ru" -> 2;
            default -> 0;
        };
    }

    static String t(String locale, String key, Object... args) {
        String[] texts = TEXTS.get(key);
        if (texts == null) {
            return key;
        }
        String text = texts[index(locale)];
        return args.length == 0 ? text : String.format(text, args);
    }

    static boolean has(String key) {
        return TEXTS.containsKey(key);
    }
}
