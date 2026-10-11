package de.buerokratiecopilot.integrations.adapters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * What each integration needs, and which of them actually exist (spec §19, G-005).
 *
 * <p>The schema already carries the honest status — one of its values is {@code NOT_SUPPORTED} — and this
 * class is what fills it. Two distinctions do the work.</p>
 *
 * <p><strong>An adapter that is declared and an adapter that is implemented are different things.</strong>
 * The specification lists integrations this software is meant to have, and a customer reading a list of
 * names has no way to tell which of them will work on Monday. Every entry here says whether it is
 * implemented in this repository, and the ones that are not are marked {@code NOT_SUPPORTED} rather than
 * left looking merely unconfigured — because "not set up yet" and "does not exist" lead a person to do
 * completely different things.</p>
 *
 * <p><strong>What the owner has to do is part of the description.</strong> An integration fails for exactly
 * one reason in most cases: nobody said who has to create the application, where the key comes from, or
 * which permission has to be granted. The steps are written next to the adapter, in the order they have to
 * be done, because that is the answer to the question that gets asked.</p>
 */
@Service
public class IntegrationAdapterCatalogue {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationAdapterCatalogue.class);

    private final JdbcTemplate jdbc;

    public IntegrationAdapterCatalogue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One adapter.
     *
     * @param implemented whether this repository contains a working adapter for it
     * @param ownerRequirements what the owner has to do before it can work, in order
     */
    public record Adapter(String provider, String category, String displayName, boolean implemented,
                          List<String> capabilities, List<String> ownerRequirements, String note) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("provider", provider);
            map.put("category", category);
            map.put("displayName", displayName);
            map.put("implemented", implemented);
            map.put("capabilities", capabilities);
            map.put("ownerRequirements", ownerRequirements);
            map.put("note", note);
            return map;
        }
    }

    /** The catalogue, in the order a person would read it. */
    public static List<Adapter> all() {
        return List.of(
                new Adapter("IMAP", "EMAIL", "E-Mail-Eingang (IMAP)", true,
                        List.of("Nachrichten abholen", "Anhänge übernehmen"),
                        List.of("Serveradresse und Port", "Benutzername", "Passwort oder App-Passwort",
                                "Bei Zwei-Faktor-Anmeldung ein app-spezifisches Passwort anlegen"),
                        "Der Eingang wird abgeholt; was daraus wird, entscheidet die Übernahme."),
                new Adapter("SMTP", "EMAIL", "E-Mail-Versand (SMTP)", true,
                        List.of("Nachrichten senden", "Zustellstatus auswerten"),
                        List.of("Serveradresse und Port", "Benutzername", "Passwort",
                                "Absenderadresse, die der Server akzeptiert"),
                        "Der Versand meldet ehrlich, ob die Nachricht angenommen wurde."),
                new Adapter("OCR_TESSERACT", "OCR", "Texterkennung (Tesseract)", true,
                        List.of("Gescannte Seiten erkennen", "Sprache wählen"),
                        List.of("Tesseract auf dem Server installieren",
                                "Sprachpakete für die benötigten Sprachen installieren"),
                        "Läuft lokal; es verlassen keine Dokumente den Server."),
                new Adapter("AI_OPENAI", "AI", "KI-Anbieter (OpenAI-kompatibel)", true,
                        List.of("Text zusammenfassen", "Entwürfe vorbereiten", "Fragen beantworten"),
                        List.of("API-Adresse", "API-Schlüssel", "Modellname",
                                "Entscheidung, welche Inhalte hinausgehen dürfen"),
                        "Ohne Schlüssel bleibt der Assistent aus; er rät nicht."),
                new Adapter("BANK_FINTS", "BANKING", "Bankumsätze (CAMT)", true,
                        List.of("Kontoauszüge im CAMT-Format einlesen", "Zahlungen zuordnen"),
                        List.of("CAMT-Dateien aus dem Online-Banking herunterladen",
                                "Ablageort für den Import festlegen"),
                        "CAMT wird gelesen; ein direkter Bankzugang ist nicht enthalten."),
                new Adapter("E_INVOICING_PROVIDER", "E_INVOICING", "E-Rechnung (ZUGFeRD, UBL, CII)", true,
                        List.of("E-Rechnungen lesen", "Rechnungen als E-Rechnung erzeugen"),
                        List.of("Format mit dem Empfänger abstimmen (ZUGFeRD, UBL oder CII)"),
                        "Gelesen und erzeugt wird, was die Normen beschreiben."),
                new Adapter("SCANNER_BRIDGE", "DEVICE", "Scanner-Anbindung", true,
                        List.of("Geräte anmelden", "Dokumente übernehmen"),
                        List.of("Gerät im Netzwerk freigeben",
                                "Zugangsdaten des Geräts hinterlegen"),
                        "Geräte werden verwaltet; ein Desktop-Programm gibt es hier nicht."),
                new Adapter("WEBHOOK", "OTHER", "Webhook", true,
                        List.of("Ereignisse empfangen", "Signatur prüfen"),
                        List.of("Geheimnis für die Signatur hinterlegen",
                                "Adresse beim Absender eintragen"),
                        "Ohne gültige Signatur wird nichts angenommen."),
                new Adapter("MICROSOFT365", "EMAIL", "Microsoft 365", false, List.of(),
                        List.of("Anwendung in Entra ID registrieren", "Weiterleitungs-URI eintragen",
                                "Berechtigungen für Mail und Kalender erteilen",
                                "Zustimmung eines Administrators einholen"),
                        "Nicht enthalten. Die Anmeldung bei Microsoft ist nicht implementiert."),
                new Adapter("GOOGLE_WORKSPACE", "EMAIL", "Google Workspace", false, List.of(),
                        List.of("Projekt in der Google Cloud Console anlegen",
                                "OAuth-Zustimmung konfigurieren", "Bereiche für Gmail und Kalender wählen",
                                "Zustimmung eines Administrators einholen"),
                        "Nicht enthalten. Die Anmeldung bei Google ist nicht implementiert."),
                new Adapter("CALENDAR_MICROSOFT", "CALENDAR", "Kalender (Microsoft)", false, List.of(),
                        List.of("Wie Microsoft 365", "Zusätzlich Calendars.ReadWrite erteilen"),
                        "Nicht enthalten."),
                new Adapter("CALENDAR_GOOGLE", "CALENDAR", "Kalender (Google)", false, List.of(),
                        List.of("Wie Google Workspace", "Zusätzlich Kalenderbereich erteilen"),
                        "Nicht enthalten."),
                new Adapter("BANK_AGGREGATOR", "BANKING", "Bankaggregator", false, List.of(),
                        List.of("Vertrag mit einem Aggregator schließen", "Zugangsdaten des Aggregators "
                                + "hinterlegen", "Freigabe der Konten durch die Kundin oder den Kunden"),
                        "Nicht enthalten. Ein Aggregator ist ein Vertrag, keine Einstellung."),
                new Adapter("ACCOUNTING_DATEV", "ACCOUNTING", "DATEV", false, List.of(),
                        List.of("Exportformat und Beraternummer klären", "Abstimmung mit der Kanzlei"),
                        "Nicht enthalten. Die Buchhaltungsübergabe ist nicht implementiert."),
                new Adapter("ACCOUNTING_LEXWARE", "ACCOUNTING", "Lexware", false, List.of(),
                        List.of("Zugang zur Schnittstelle klären"),
                        "Nicht enthalten."),
                new Adapter("ACCOUNTING_SEVDESK", "ACCOUNTING", "SEVDesk", false, List.of(),
                        List.of("API-Schlüssel im Konto erzeugen"),
                        "Nicht enthalten."),
                new Adapter("CRM_HUBSPOT", "CRM", "HubSpot", false, List.of(),
                        List.of("Private App anlegen", "Zugriffstoken hinterlegen"),
                        "Nicht enthalten."),
                new Adapter("CRM_SALESFORCE", "CRM", "Salesforce", false, List.of(),
                        List.of("Connected App anlegen", "Client-ID und Geheimnis hinterlegen"),
                        "Nicht enthalten."),
                new Adapter("E_SIGNATURE_DOCUSIGN", "SIGNATURE", "DocuSign", false, List.of(),
                        List.of("Integrationsschlüssel anlegen", "Weiterleitungs-URI eintragen"),
                        "Nicht enthalten. Signaturen sind nicht implementiert."),
                new Adapter("E_SIGNATURE_SKRIBBLE", "SIGNATURE", "Skribble", false, List.of(),
                        List.of("API-Zugang beantragen"),
                        "Nicht enthalten."),
                new Adapter("CLOUD_STORAGE_DRIVE", "STORAGE", "Google Drive", false, List.of(),
                        List.of("Wie Google Workspace", "Zusätzlich Drive-Bereich erteilen"),
                        "Nicht enthalten."),
                new Adapter("CLOUD_STORAGE_ONEDRIVE", "STORAGE", "OneDrive", false, List.of(),
                        List.of("Wie Microsoft 365", "Zusätzlich Files.ReadWrite erteilen"),
                        "Nicht enthalten."),
                new Adapter("OCR_CLOUD", "OCR", "Texterkennung in der Cloud", false, List.of(),
                        List.of("Anbieter wählen", "Zugangsdaten hinterlegen",
                                "Entscheiden, ob Dokumente das Haus verlassen dürfen"),
                        "Nicht enthalten. Die lokale Erkennung ist die enthaltene."),
                new Adapter("AI_AZURE_OPENAI", "AI", "Azure OpenAI", false, List.of(),
                        List.of("Ressource anlegen", "Bereitstellung und Schlüssel hinterlegen"),
                        "Nicht enthalten; der OpenAI-kompatible Anbieter deckt den Fall meist ab."),
                new Adapter("AI_OLLAMA", "AI", "Ollama (lokal)", false, List.of(),
                        List.of("Ollama auf einem Server betreiben", "Adresse hinterlegen"),
                        "Nicht enthalten; der OpenAI-kompatible Anbieter spricht auch mit lokalen Servern."),
                new Adapter("AI_ANTHROPIC", "AI", "Anthropic", false, List.of(),
                        List.of("Schlüssel erzeugen"),
                        "Nicht enthalten."),
                new Adapter("AI_CUSTOM", "AI", "Eigener KI-Anbieter", false, List.of(),
                        List.of("Adresse und Schlüssel hinterlegen",
                                "Schnittstelle prüfen: sie muss OpenAI-kompatibel sein"),
                        "Nicht enthalten als eigener Anbieter; über den OpenAI-kompatiblen Anbieter nutzbar."),
                new Adapter("CUSTOM", "OTHER", "Eigene Anbindung", false, List.of(),
                        List.of("Schnittstelle selbst entwickeln"),
                        "Nicht enthalten."));
    }

    /** One adapter by provider name. */
    public static Adapter byProvider(String provider) {
        return all().stream().filter(adapter -> adapter.provider().equals(provider)).findFirst()
                .orElseThrow(() -> ApiException.notFound("integration adapter"));
    }

    /** What is actually implemented, for a caller that reports on it. */
    public static List<Adapter> implemented() {
        return all().stream().filter(Adapter::implemented).toList();
    }

    /** What is declared in the specification but not implemented here. */
    public static List<Adapter> notImplemented() {
        return all().stream().filter(adapter -> !adapter.implemented()).toList();
    }

    /** The catalogue as the interface would show it, grouped by category. */
    public Map<String, Object> catalogue() {
        Map<String, Object> grouped = new LinkedHashMap<>();
        for (Adapter adapter : all()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) grouped
                    .computeIfAbsent(adapter.category(), key -> new ArrayList<Map<String, Object>>());
            entries.add(adapter.asMap());
        }
        Map<String, Object> result = new LinkedHashMap<>(grouped);
        result.put("implementedCount", implemented().size());
        result.put("notImplementedCount", notImplemented().size());
        return result;
    }

    /**
     * Records that a customer asked for an adapter this software does not have.
     *
     * <p>Stored with the schema's own {@code NOT_SUPPORTED} status rather than refused outright: the
     * customer's wish is worth keeping, and a row that says "not supported" is the honest answer that a
     * row saying "not configured" is not — the first tells a person to look for another way, the second
     * sends them looking for a setting that does not exist.</p>
     */
    @Transactional
    public Map<String, Object> declareUnsupported(java.util.UUID tenantId, java.util.UUID workspaceId,
                                                  String provider, java.util.UUID requestedBy) {
        Adapter adapter = byProvider(provider);
        if (adapter.implemented()) {
            throw ApiException.conflict("Diese Anbindung ist vorhanden und wird normal eingerichtet");
        }

        java.util.List<java.util.UUID> existing = jdbc.query("""
                select id from integrations where tenant_id = ? and provider = ? and deleted_at is null
                """, (rs, row) -> rs.getObject("id", java.util.UUID.class), tenantId, provider);
        java.util.UUID integrationId = existing.isEmpty()
                ? jdbc.queryForObject("""
                        insert into integrations (id, tenant_id, workspace_id, provider, category,
                                                 display_name, status, config, granted_scopes,
                                                 requires_owner_action, owner_action_hint, is_enabled,
                                                 connected_by)
                        values (gen_random_uuid(), ?, ?, ?, ?, ?, 'NOT_SUPPORTED', '{}'::jsonb, '{}',
                                true, ?, false, ?)
                        returning id
                        """, java.util.UUID.class, tenantId, workspaceId, provider, adapter.category(),
                        adapter.displayName(), String.join(" · ", adapter.ownerRequirements()), requestedBy)
                : existing.getFirst();

        LOG.info("Integration {} ({}) recorded as NOT_SUPPORTED for tenant {}", provider,
                adapter.displayName(), tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("integrationId", integrationId.toString());
        result.put("provider", provider);
        result.put("status", "NOT_SUPPORTED");
        result.put("note", adapter.note());
        result.put("ownerRequirements", adapter.ownerRequirements());
        return result;
    }

    /** What a tenant has connected, with the honest status of each. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> connections(java.util.UUID tenantId) {
        return jdbc.queryForList("""
                select provider, category, display_name, status, requires_owner_action, owner_action_hint,
                       last_sync_status, consecutive_failures
                from integrations where tenant_id = ? and deleted_at is null order by category, provider
                """, tenantId);
    }

    /** The requirements of one adapter, which is the answer to "what do we have to do". */
    public static Map<String, Object> requirementsOf(String provider) {
        Adapter adapter = byProvider(provider);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", adapter.provider());
        map.put("displayName", adapter.displayName());
        map.put("implemented", adapter.implemented());
        map.put("steps", adapter.ownerRequirements());
        map.put("capabilities", adapter.capabilities());
        map.put("note", adapter.note());
        if (!adapter.implemented()) {
            map.put("conclusion", "Diese Anbindung ist in dieser Fassung nicht enthalten. Die Schritte "
                    + "beschreiben, was für eine eigene Anbindung nötig wäre.");
        }
        return map;
    }

    /** Refuses an adapter that does not exist, so that a typo is not stored as a wish. */
    static void requireKnown(String provider) {
        if (provider == null || all().stream().noneMatch(adapter -> adapter.provider().equals(provider))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Anbindung: " + provider);
        }
    }
}
