/* =============================================================================
   Robots Runtime — administration page
   Plain browser JavaScript: no framework, no bundler, no npm dependency.

   Security rules this file follows:
     * the admin token lives in sessionStorage only (never localStorage),
     * the token is never written to the console and never built into a URL,
     * server data is inserted with textContent, never with innerHTML,
     * a freshly issued token is shown once and removed from the DOM when the
       operator confirms they saved it.
   ============================================================================= */

(() => {
  'use strict';

  const TOKEN_KEY = 'runtime-admin.token';
  const LANG_KEY = 'runtime-admin.lang';
  const BRIDGE_URL_KEY = 'runtime-admin.bridge-url';
  const LANGUAGES = ['de', 'en', 'ru'];
  const DEFAULT_SCOPES = ['agents.view', 'agents.run', 'documents:write'];

  /* --------------------------------------------------------------- dictionary */

  const I18N = {
    en: {
      'app.title': 'Robots Runtime',
      'app.subtitle': 'Installation administration',
      'app.skip': 'Skip to content',
      'nav.language': 'Language',

      'auth.title': 'Administrator access',
      'auth.hint': 'The runtime admin token is issued by the installer (BC_RUNTIME_ADMIN_TOKEN).',
      'auth.tokenLabel': 'Admin token',
      'auth.tokenPlaceholder': 'X-Runtime-Admin-Token',
      'auth.check': 'Check',
      'auth.checking': 'Checking…',
      'auth.forget': 'Forget',
      'auth.notChecked': 'not checked',
      'auth.connected': 'connected',
      'auth.invalid': 'wrong token',
      'auth.security': "The token is kept in this tab's session storage only. It is never written to localStorage and never logged.",
      'auth.tokenRequired': 'Enter the admin token first.',
      'auth.forgotten': 'The token was removed from this tab.',

      'common.refresh': 'Refresh',
      'common.copy': 'Copy',
      'common.copied': 'Copied',
      'common.copyFailed': 'Copy failed',
      'common.yes': 'Yes',
      'common.no': 'No',
      'common.cancel': 'Cancel',
      'common.notAvailable': '—',

      'status.title': 'Installation status',
      'status.version': 'Version',
      'status.instanceId': 'Instance ID',
      'status.health': 'Health',
      'status.bootstrapped': 'Set up',
      'status.tenantId': 'Tenant',
      'status.workspaceId': 'Workspace',
      'status.ownerEmail': 'Owner e-mail',
      'status.activeTokens': 'Active tokens',
      'status.healthUp': 'up',
      'status.healthDown': 'down',
      'status.healthUnknown': 'unknown',
      'status.healthUnreachable': 'no answer',

      'license.title': 'Licence',
      'license.hint': 'Paste the licence key the vendor sent you. It is verified before it replaces the current key.',
      'license.label': 'Licence key',
      'license.placeholder': 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…',
      'license.install': 'Install',
      'license.installing': 'Installing…',
      'license.storageHint': 'The key is stored at /app/data/license.key with owner-only permissions.',
      'license.empty': 'Paste the licence key first.',
      'license.installed': 'Licence installed',
      'license.installedHint': 'The new key is active in the running instance.',
      'license.file': 'Stored at',
      'license.warnings': 'Notes for the operator',
      'license.plan': 'Plan',
      'license.customer': 'Customer',
      'license.expires': 'Valid until',
      'license.perpetual': 'perpetual licence',
      'license.daysLeft': '{days} day(s) left',
      'license.graceDaysLeft': '{days} grace day(s) left',
      'license.expired': 'expired',
      'license.revokedAt': 'Revoked on {date}',
      'license.state.LICENSE_ACTIVE': 'active',
      'license.state.LICENSE_GRACE': 'grace period',
      'license.state.LICENSE_READ_ONLY': 'read-only',
      'license.state.LICENSE_REVOKED': 'revoked',
      'license.state.LICENSE_MISSING': 'missing',
      'license.state.LICENSE_NOT_ENFORCED': 'not enforced',
      'license.state.LICENSE_UNKNOWN': 'unknown',

      'license.reason.MALFORMED': 'The key is not a licence token (three dot-separated base64url parts).',
      'license.reason.UNSUPPORTED_ALGORITHM': 'The key uses an unsupported signature algorithm; only HS256 licences are accepted.',
      'license.reason.BAD_SIGNATURE': "The signature does not match this installation's licence secret.",
      'license.reason.UNSUPPORTED_VERSION': 'The key uses an unsupported licence version.',
      'license.reason.WRONG_INSTANCE': 'The key belongs to a different installation (instance ID mismatch).',
      'license.reason.INVALID_PAYLOAD': 'The key payload is incomplete or inconsistent.',
      'license.reason.EMPTY': 'The licence key is empty.',
      'license.reason.TOO_LARGE': 'The key is too large to be a licence token.',
      'license.reason.LICENSE_SECRET_MISSING': 'LICENSE_SECRET is not configured on the server, so no key can be verified.',
      'license.reason.LICENSE_SECRET_TOO_SHORT': 'LICENSE_SECRET is shorter than 32 characters.',
      'license.reason.INSTANCE_ID_MISSING': 'The instance identifier file is missing or empty.',
      'license.reason.INVALID_KEY_FILE': 'The configured licence key path is not usable.',
      'license.reason.KEY_FILE_NOT_WRITABLE': 'The server could not write the licence key file.',
      'license.reason.NOT_LOADED': 'The key was written but the running instance could not load it.',
      'license.warn.ENV_KEY_OVERRIDES': 'LICENSE_KEY is also set in the environment and wins again after a restart. Remove it or point LICENSE_KEY_FILE at the key file.',
      'license.warn.KEY_FILE_NOT_CONFIGURED': 'LICENSE_KEY_FILE is not configured. Set it so the key is read again after a restart.',
      'license.warn.ENFORCEMENT_DISABLED': 'Licence enforcement is disabled; the stored key is not enforced.',
      'license.warn.READ_ONLY': 'The licence is expired beyond the grace period; agent execution stays read-only.',
      'license.warn.REVOKED': 'The vendor revoked this licence; agent execution stays disabled.',

      'bootstrap.title': 'First-time setup',
      'bootstrap.hint': 'Creates the enterprise, its workspace, the owner and the first bridge token. This happens once per installation.',
      'bootstrap.tenantName': 'Enterprise name',
      'bootstrap.tenantPlaceholder': 'Muster GmbH',
      'bootstrap.ownerEmail': 'Owner e-mail',
      'bootstrap.emailPlaceholder': 'admin@example.de',
      'bootstrap.ownerName': 'Owner name (optional)',
      'bootstrap.namePlaceholder': 'Admin Muster',
      'bootstrap.submit': 'Create installation',
      'bootstrap.submitting': 'Creating…',
      'bootstrap.done': 'This installation is set up.',
      'bootstrap.doneHint': 'Additional tokens can be issued below.',
      'bootstrap.resultTitle': 'Installation created',
      'bootstrap.alreadyExists': 'The installation was already set up. Use the token section to issue another token.',
      'bootstrap.tokenWarning': "This token is shown once. Store it in the bridge's secret store; only its hash is kept on the server.",

      'tokens.title': 'API tokens',
      'tokens.hint': "Tokens authenticate the bridge and the customer's own integrations. The plaintext is shown exactly once.",
      'tokens.notBootstrapped': 'Set up the installation first.',
      'tokens.notBootstrappedHint': 'Token administration needs a tenant, a workspace and an owner.',
      'tokens.name': 'Token name',
      'tokens.namePlaceholder': 'bridge-office-1',
      'tokens.scopes': 'Scopes',
      'tokens.scopesHint': 'Give documents:write for the bridge, agents.run for running agents, settings:write only to trusted integrations.',
      'tokens.create': 'Create token',
      'tokens.creating': 'Creating…',
      'tokens.listTitle': 'Issued tokens',
      'tokens.empty': 'No tokens have been issued yet.',
      'tokens.colName': 'Name',
      'tokens.colScopes': 'Scopes',
      'tokens.colCreated': 'Created',
      'tokens.colLastUsed': 'Last used',
      'tokens.colStatus': 'Status',
      'tokens.colActions': 'Actions',
      'tokens.active': 'active',
      'tokens.revoked': 'revoked',
      'tokens.revoke': 'Revoke',
      'tokens.revokeTitle': 'Revoke token?',
      'tokens.revokeQuestion': 'The token',
      'tokens.revokeQuestionEnd': 'stops working immediately. This cannot be undone.',
      'tokens.revokeConfirm': 'Revoke',
      'tokens.revoking': 'Revoking…',
      'tokens.revokedNotice': 'Token revoked. Requests with it now answer 401.',
      'tokens.created': 'Token created',
      'tokens.nameRequired': 'Give the token a name.',
      'tokens.scopesRequired': 'Select at least one scope.',
      'tokens.never': 'never',

      'bridge.title': 'Connect the bridge',
      'bridge.hint': 'Add this block to bridge.config.json next to the console settings. The bridge posts captured documents to the runtime ingest endpoint.',
      'bridge.url': 'Runtime base URL',
      'bridge.urlPlaceholder': 'https://runtime.example.de',
      'bridge.jsonTitle': 'forward.copilot',
      'bridge.dockerTitle': 'Docker commands',
      'bridge.tokenHint': "Set RUNTIME_INGEST_TOKEN in the bridge's .env (mode 600). The token needs the documents:write scope.",
      'bridge.dockerHint': 'Replace the image tag with the version your installation runs. Run the commands on the bridge machine, not on the runtime server.',

      'once.badge': 'shown once',
      'once.title': 'Copy this token now',
      'once.warning': 'It is not stored in readable form and cannot be shown again.',
      'once.saved': 'I have saved it',

      'error.400': 'The request was rejected.',
      'error.401': "The admin token is not correct. Check BC_RUNTIME_ADMIN_TOKEN in the installation's environment file.",
      'error.402': 'The licence is not active. Install a valid licence key first.',
      'error.403': 'This token is not allowed to do that.',
      'error.404': 'The runtime admin API is not enabled on this installation (or the installation is already set up).',
      'error.409': 'The action conflicts with the current state.',
      'error.422': 'The input is not valid.',
      'error.429': 'Too many requests. Wait a moment and try again.',
      'error.500': 'The server could not complete the action. Check the API logs with the correlation ID.',
      'error.network': 'The server did not answer. Check that the API is running and reachable.',
      'error.unexpected': 'Something unexpected happened.',

      'footer.note': 'Robots Runtime administration. This page runs locally on your server; no data leaves the installation unless you enable an external channel.'
    },

    de: {
      'app.title': 'Robots Runtime',
      'app.subtitle': 'Installationsverwaltung',
      'app.skip': 'Zum Inhalt springen',
      'nav.language': 'Sprache',

      'auth.title': 'Administratorzugang',
      'auth.hint': 'Der Runtime-Admin-Token wird vom Installer erzeugt (BC_RUNTIME_ADMIN_TOKEN).',
      'auth.tokenLabel': 'Admin-Token',
      'auth.tokenPlaceholder': 'X-Runtime-Admin-Token',
      'auth.check': 'Prüfen',
      'auth.checking': 'Wird geprüft …',
      'auth.forget': 'Vergessen',
      'auth.notChecked': 'nicht geprüft',
      'auth.connected': 'verbunden',
      'auth.invalid': 'Token falsch',
      'auth.security': 'Der Token liegt nur im sessionStorage dieses Tabs. Er wird nicht in localStorage geschrieben und nicht protokolliert.',
      'auth.tokenRequired': 'Bitte zuerst den Admin-Token eingeben.',
      'auth.forgotten': 'Der Token wurde aus diesem Tab entfernt.',

      'common.refresh': 'Aktualisieren',
      'common.copy': 'Kopieren',
      'common.copied': 'Kopiert',
      'common.copyFailed': 'Kopieren fehlgeschlagen',
      'common.yes': 'Ja',
      'common.no': 'Nein',
      'common.cancel': 'Abbrechen',
      'common.notAvailable': '—',

      'status.title': 'Installationsstatus',
      'status.version': 'Version',
      'status.instanceId': 'Instanz-ID',
      'status.health': 'Zustand',
      'status.bootstrapped': 'Eingerichtet',
      'status.tenantId': 'Mandant',
      'status.workspaceId': 'Workspace',
      'status.ownerEmail': 'E-Mail des Inhabers',
      'status.activeTokens': 'Aktive Tokens',
      'status.healthUp': 'erreichbar',
      'status.healthDown': 'gestört',
      'status.healthUnknown': 'unbekannt',
      'status.healthUnreachable': 'keine Antwort',

      'license.title': 'Lizenz',
      'license.hint': 'Fügen Sie den Lizenzschlüssel des Anbieters ein. Er wird geprüft, bevor er den aktuellen Schlüssel ersetzt.',
      'license.label': 'Lizenzschlüssel',
      'license.placeholder': 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…',
      'license.install': 'Installieren',
      'license.installing': 'Wird installiert …',
      'license.storageHint': 'Der Schlüssel liegt unter /app/data/license.key mit Rechten nur für den Eigentümer.',
      'license.empty': 'Bitte zuerst den Lizenzschlüssel einfügen.',
      'license.installed': 'Lizenz installiert',
      'license.installedHint': 'Der neue Schlüssel ist in der laufenden Instanz aktiv.',
      'license.file': 'Gespeichert unter',
      'license.warnings': 'Hinweise für den Betreiber',
      'license.plan': 'Tarif',
      'license.customer': 'Kunde',
      'license.expires': 'Gültig bis',
      'license.perpetual': 'unbefristete Lizenz',
      'license.daysLeft': 'noch {days} Tag(e)',
      'license.graceDaysLeft': 'noch {days} Tag(e) Kulanz',
      'license.expired': 'abgelaufen',
      'license.revokedAt': 'Widerrufen am {date}',
      'license.state.LICENSE_ACTIVE': 'aktiv',
      'license.state.LICENSE_GRACE': 'Kulanzfrist',
      'license.state.LICENSE_READ_ONLY': 'schreibgeschützt',
      'license.state.LICENSE_REVOKED': 'widerrufen',
      'license.state.LICENSE_MISSING': 'fehlt',
      'license.state.LICENSE_NOT_ENFORCED': 'Prüfung aus',
      'license.state.LICENSE_UNKNOWN': 'unbekannt',

      'license.reason.MALFORMED': 'Der Schlüssel ist kein Lizenz-Token (drei durch Punkte getrennte base64url-Teile).',
      'license.reason.UNSUPPORTED_ALGORITHM': 'Der Schlüssel nutzt ein nicht unterstütztes Signaturverfahren; akzeptiert wird nur HS256.',
      'license.reason.BAD_SIGNATURE': 'Die Signatur passt nicht zum Lizenz-Secret dieser Installation.',
      'license.reason.UNSUPPORTED_VERSION': 'Der Schlüssel nutzt eine nicht unterstützte Lizenzversion.',
      'license.reason.WRONG_INSTANCE': 'Der Schlüssel gehört zu einer anderen Installation (Instanz-ID stimmt nicht).',
      'license.reason.INVALID_PAYLOAD': 'Der Inhalt des Schlüssels ist unvollständig oder widersprüchlich.',
      'license.reason.EMPTY': 'Der Lizenzschlüssel ist leer.',
      'license.reason.TOO_LARGE': 'Der Schlüssel ist zu groß für einen Lizenz-Token.',
      'license.reason.LICENSE_SECRET_MISSING': 'LICENSE_SECRET ist auf dem Server nicht gesetzt; ein Schlüssel kann nicht geprüft werden.',
      'license.reason.LICENSE_SECRET_TOO_SHORT': 'LICENSE_SECRET ist kürzer als 32 Zeichen.',
      'license.reason.INSTANCE_ID_MISSING': 'Die Datei mit der Instanz-ID fehlt oder ist leer.',
      'license.reason.INVALID_KEY_FILE': 'Der konfigurierte Pfad für die Lizenzdatei ist nicht nutzbar.',
      'license.reason.KEY_FILE_NOT_WRITABLE': 'Der Server konnte die Lizenzdatei nicht schreiben.',
      'license.reason.NOT_LOADED': 'Der Schlüssel wurde gespeichert, aber die laufende Instanz konnte ihn nicht laden.',
      'license.warn.ENV_KEY_OVERRIDES': 'LICENSE_KEY ist zusätzlich in der Umgebung gesetzt und gilt nach einem Neustart wieder. Entfernen Sie ihn oder setzen Sie LICENSE_KEY_FILE auf die Datei.',
      'license.warn.KEY_FILE_NOT_CONFIGURED': 'LICENSE_KEY_FILE ist nicht gesetzt. Setzen Sie ihn, damit der Schlüssel nach einem Neustart wieder gelesen wird.',
      'license.warn.ENFORCEMENT_DISABLED': 'Die Lizenzprüfung ist ausgeschaltet; der gespeicherte Schlüssel wird nicht erzwungen.',
      'license.warn.READ_ONLY': 'Die Lizenz ist über die Kulanzfrist hinaus abgelaufen; die Ausführung bleibt schreibgeschützt.',
      'license.warn.REVOKED': 'Der Anbieter hat die Lizenz widerrufen; die Ausführung bleibt gesperrt.',

      'bootstrap.title': 'Ersteinrichtung',
      'bootstrap.hint': 'Legt Unternehmen, Workspace, Inhaber und den ersten Bridge-Token an. Das passiert einmal pro Installation.',
      'bootstrap.tenantName': 'Unternehmensname',
      'bootstrap.tenantPlaceholder': 'Muster GmbH',
      'bootstrap.ownerEmail': 'E-Mail des Inhabers',
      'bootstrap.emailPlaceholder': 'admin@example.de',
      'bootstrap.ownerName': 'Name des Inhabers (optional)',
      'bootstrap.namePlaceholder': 'Admin Muster',
      'bootstrap.submit': 'Installation anlegen',
      'bootstrap.submitting': 'Wird angelegt …',
      'bootstrap.done': 'Diese Installation ist eingerichtet.',
      'bootstrap.doneHint': 'Weitere Tokens können unten ausgestellt werden.',
      'bootstrap.resultTitle': 'Installation angelegt',
      'bootstrap.alreadyExists': 'Die Installation wurde bereits eingerichtet. Nutzen Sie den Token-Bereich, um einen weiteren Token auszustellen.',
      'bootstrap.tokenWarning': 'Dieser Token wird nur einmal angezeigt. Bewahren Sie ihn im Secret-Store der Bridge auf; auf dem Server liegt nur sein Hash.',

      'tokens.title': 'API-Tokens',
      'tokens.hint': 'Tokens authentifizieren die Bridge und die Integrationen des Kunden. Der Klartext wird genau einmal angezeigt.',
      'tokens.notBootstrapped': 'Richten Sie zuerst die Installation ein.',
      'tokens.notBootstrappedHint': 'Die Token-Verwaltung braucht Mandant, Workspace und Inhaber.',
      'tokens.name': 'Token-Name',
      'tokens.namePlaceholder': 'bridge-office-1',
      'tokens.scopes': 'Scopes',
      'tokens.scopesHint': 'documents:write für die Bridge, agents.run für Agentenläufe, settings:write nur für vertrauenswürdige Integrationen.',
      'tokens.create': 'Token erstellen',
      'tokens.creating': 'Wird erstellt …',
      'tokens.listTitle': 'Ausgestellte Tokens',
      'tokens.empty': 'Es wurden noch keine Tokens ausgestellt.',
      'tokens.colName': 'Name',
      'tokens.colScopes': 'Scopes',
      'tokens.colCreated': 'Erstellt',
      'tokens.colLastUsed': 'Zuletzt verwendet',
      'tokens.colStatus': 'Status',
      'tokens.colActions': 'Aktionen',
      'tokens.active': 'aktiv',
      'tokens.revoked': 'widerrufen',
      'tokens.revoke': 'Widerrufen',
      'tokens.revokeTitle': 'Token widerrufen?',
      'tokens.revokeQuestion': 'Der Token',
      'tokens.revokeQuestionEnd': 'verliert sofort seine Gültigkeit. Das lässt sich nicht rückgängig machen.',
      'tokens.revokeConfirm': 'Widerrufen',
      'tokens.revoking': 'Wird widerrufen …',
      'tokens.revokedNotice': 'Token widerrufen. Anfragen damit erhalten ab sofort 401.',
      'tokens.created': 'Token erstellt',
      'tokens.nameRequired': 'Geben Sie dem Token einen Namen.',
      'tokens.scopesRequired': 'Wählen Sie mindestens einen Scope.',
      'tokens.never': 'nie',

      'bridge.title': 'Bridge verbinden',
      'bridge.hint': 'Fügen Sie diesen Block neben den Console-Einstellungen in bridge.config.json ein. Die Bridge sendet erfasste Dokumente an den Ingest-Endpunkt.',
      'bridge.url': 'Basis-URL der Runtime',
      'bridge.urlPlaceholder': 'https://runtime.example.de',
      'bridge.jsonTitle': 'forward.copilot',
      'bridge.dockerTitle': 'Docker-Befehle',
      'bridge.tokenHint': 'Setzen Sie RUNTIME_INGEST_TOKEN in der .env der Bridge (Rechte 600). Der Token braucht den Scope documents:write.',
      'bridge.dockerHint': 'Ersetzen Sie den Image-Tag durch die Version Ihrer Installation. Führen Sie die Befehle auf dem Bridge-Rechner aus, nicht auf dem Runtime-Server.',

      'once.badge': 'einmalig',
      'once.title': 'Token jetzt kopieren',
      'once.warning': 'Er wird nicht lesbar gespeichert und kann nicht erneut angezeigt werden.',
      'once.saved': 'Ich habe ihn gespeichert',

      'error.400': 'Die Anfrage wurde abgelehnt.',
      'error.401': 'Der Admin-Token ist nicht korrekt. Prüfen Sie BC_RUNTIME_ADMIN_TOKEN in der Umgebungsdatei der Installation.',
      'error.402': 'Die Lizenz ist nicht aktiv. Installieren Sie zuerst einen gültigen Lizenzschlüssel.',
      'error.403': 'Dieser Token darf das nicht.',
      'error.404': 'Die Runtime-Admin-API ist auf dieser Installation nicht aktiviert (oder die Installation ist bereits eingerichtet).',
      'error.409': 'Die Aktion steht im Konflikt mit dem aktuellen Zustand.',
      'error.422': 'Die Eingabe ist ungültig.',
      'error.429': 'Zu viele Anfragen. Bitte kurz warten.',
      'error.500': 'Der Server konnte die Aktion nicht abschließen. Prüfen Sie die API-Logs mit der Correlation-ID.',
      'error.network': 'Der Server hat nicht geantwortet. Prüfen Sie, ob die API läuft und erreichbar ist.',
      'error.unexpected': 'Unerwarteter Fehler.',

      'footer.note': 'Robots-Runtime-Verwaltung. Diese Seite läuft lokal auf Ihrem Server; Daten verlassen die Installation nur, wenn Sie einen externen Kanal freischalten.'
    },

    ru: {
      'app.title': 'Robots Runtime',
      'app.subtitle': 'Администрирование установки',
      'app.skip': 'Перейти к содержимому',
      'nav.language': 'Язык',

      'auth.title': 'Доступ администратора',
      'auth.hint': 'Admin-токен runtime выдаёт установщик (BC_RUNTIME_ADMIN_TOKEN).',
      'auth.tokenLabel': 'Admin-токен',
      'auth.tokenPlaceholder': 'X-Runtime-Admin-Token',
      'auth.check': 'Проверить',
      'auth.checking': 'Проверка…',
      'auth.forget': 'Забыть',
      'auth.notChecked': 'не проверен',
      'auth.connected': 'подключено',
      'auth.invalid': 'неверный токен',
      'auth.security': 'Токен хранится только в sessionStorage этой вкладки. Он не пишется в localStorage и не попадает в логи.',
      'auth.tokenRequired': 'Сначала введите admin-токен.',
      'auth.forgotten': 'Токен удалён из этой вкладки.',

      'common.refresh': 'Обновить',
      'common.copy': 'Скопировать',
      'common.copied': 'Скопировано',
      'common.copyFailed': 'Не удалось скопировать',
      'common.yes': 'Да',
      'common.no': 'Нет',
      'common.cancel': 'Отмена',
      'common.notAvailable': '—',

      'status.title': 'Статус установки',
      'status.version': 'Версия',
      'status.instanceId': 'ID инстанса',
      'status.health': 'Состояние',
      'status.bootstrapped': 'Настроена',
      'status.tenantId': 'Тенант',
      'status.workspaceId': 'Рабочая область',
      'status.ownerEmail': 'E-mail владельца',
      'status.activeTokens': 'Активные токены',
      'status.healthUp': 'работает',
      'status.healthDown': 'сбой',
      'status.healthUnknown': 'неизвестно',
      'status.healthUnreachable': 'нет ответа',

      'license.title': 'Лицензия',
      'license.hint': 'Вставьте лицензионный ключ от поставщика. Он проверяется до замены текущего ключа.',
      'license.label': 'Лицензионный ключ',
      'license.placeholder': 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9…',
      'license.install': 'Установить',
      'license.installing': 'Установка…',
      'license.storageHint': 'Ключ сохраняется в /app/data/license.key с правами только для владельца.',
      'license.empty': 'Сначала вставьте лицензионный ключ.',
      'license.installed': 'Лицензия установлена',
      'license.installedHint': 'Новый ключ уже действует в запущенном инстансе.',
      'license.file': 'Сохранён в',
      'license.warnings': 'Примечания для администратора',
      'license.plan': 'Тариф',
      'license.customer': 'Клиент',
      'license.expires': 'Действует до',
      'license.perpetual': 'бессрочная лицензия',
      'license.daysLeft': 'осталось {days} дн.',
      'license.graceDaysLeft': 'льготный период: {days} дн.',
      'license.expired': 'истекла',
      'license.revokedAt': 'Отозвана {date}',
      'license.state.LICENSE_ACTIVE': 'активна',
      'license.state.LICENSE_GRACE': 'льготный период',
      'license.state.LICENSE_READ_ONLY': 'только чтение',
      'license.state.LICENSE_REVOKED': 'отозвана',
      'license.state.LICENSE_MISSING': 'отсутствует',
      'license.state.LICENSE_NOT_ENFORCED': 'проверка выключена',
      'license.state.LICENSE_UNKNOWN': 'неизвестно',

      'license.reason.MALFORMED': 'Ключ не является лицензионным токеном (три части через точку, base64url).',
      'license.reason.UNSUPPORTED_ALGORITHM': 'Ключ использует неподдерживаемый алгоритм подписи; принимается только HS256.',
      'license.reason.BAD_SIGNATURE': 'Подпись не совпадает с секретом лицензии этой установки.',
      'license.reason.UNSUPPORTED_VERSION': 'Неподдерживаемая версия лицензии.',
      'license.reason.WRONG_INSTANCE': 'Ключ принадлежит другой установке (ID инстанса не совпадает).',
      'license.reason.INVALID_PAYLOAD': 'Содержимое ключа неполное или противоречивое.',
      'license.reason.EMPTY': 'Лицензионный ключ пуст.',
      'license.reason.TOO_LARGE': 'Ключ слишком велик для лицензионного токена.',
      'license.reason.LICENSE_SECRET_MISSING': 'На сервере не задан LICENSE_SECRET, поэтому ключ нельзя проверить.',
      'license.reason.LICENSE_SECRET_TOO_SHORT': 'LICENSE_SECRET короче 32 символов.',
      'license.reason.INSTANCE_ID_MISSING': 'Файл с ID инстанса отсутствует или пуст.',
      'license.reason.INVALID_KEY_FILE': 'Настроенный путь к файлу лицензии непригоден.',
      'license.reason.KEY_FILE_NOT_WRITABLE': 'Сервер не смог записать файл лицензии.',
      'license.reason.NOT_LOADED': 'Ключ сохранён, но запущенный инстанс не смог его загрузить.',
      'license.warn.ENV_KEY_OVERRIDES': 'LICENSE_KEY также задан в окружении и снова вступит в силу после перезапуска. Удалите его или укажите LICENSE_KEY_FILE на файл с ключом.',
      'license.warn.KEY_FILE_NOT_CONFIGURED': 'LICENSE_KEY_FILE не задан. Укажите его, чтобы ключ читался после перезапуска.',
      'license.warn.ENFORCEMENT_DISABLED': 'Проверка лицензии выключена; сохранённый ключ не применяется.',
      'license.warn.READ_ONLY': 'Лицензия истекла и льготный период закончился; выполнение остаётся в режиме только чтения.',
      'license.warn.REVOKED': 'Поставщик отозвал лицензию; выполнение остаётся заблокированным.',

      'bootstrap.title': 'Первичная настройка',
      'bootstrap.hint': 'Создаёт предприятие, рабочую область, владельца и первый токен моста. Выполняется один раз на установку.',
      'bootstrap.tenantName': 'Название предприятия',
      'bootstrap.tenantPlaceholder': 'Muster GmbH',
      'bootstrap.ownerEmail': 'E-mail владельца',
      'bootstrap.emailPlaceholder': 'admin@example.de',
      'bootstrap.ownerName': 'Имя владельца (необязательно)',
      'bootstrap.namePlaceholder': 'Admin Muster',
      'bootstrap.submit': 'Создать установку',
      'bootstrap.submitting': 'Создание…',
      'bootstrap.done': 'Установка уже настроена.',
      'bootstrap.doneHint': 'Дополнительные токены можно выпустить ниже.',
      'bootstrap.resultTitle': 'Установка создана',
      'bootstrap.alreadyExists': 'Установка уже была настроена. Выпустите новый токен в разделе токенов.',
      'bootstrap.tokenWarning': 'Этот токен показывается один раз. Сохраните его в хранилище секретов моста; на сервере остаётся только его хеш.',

      'tokens.title': 'API-токены',
      'tokens.hint': 'Токены аутентифицируют мост и интеграции клиента. Открытый текст показывается ровно один раз.',
      'tokens.notBootstrapped': 'Сначала настройте установку.',
      'tokens.notBootstrappedHint': 'Для управления токенами нужны тенант, рабочая область и владелец.',
      'tokens.name': 'Имя токена',
      'tokens.namePlaceholder': 'bridge-office-1',
      'tokens.scopes': 'Разрешения (scopes)',
      'tokens.scopesHint': 'documents:write — для моста, agents.run — для запуска агентов, settings:write — только доверенным интеграциям.',
      'tokens.create': 'Создать токен',
      'tokens.creating': 'Создание…',
      'tokens.listTitle': 'Выпущенные токены',
      'tokens.empty': 'Токены ещё не выпускались.',
      'tokens.colName': 'Имя',
      'tokens.colScopes': 'Разрешения',
      'tokens.colCreated': 'Создан',
      'tokens.colLastUsed': 'Последнее использование',
      'tokens.colStatus': 'Состояние',
      'tokens.colActions': 'Действия',
      'tokens.active': 'активен',
      'tokens.revoked': 'отозван',
      'tokens.revoke': 'Отозвать',
      'tokens.revokeTitle': 'Отозвать токен?',
      'tokens.revokeQuestion': 'Токен',
      'tokens.revokeQuestionEnd': 'перестанет работать сразу. Отменить это нельзя.',
      'tokens.revokeConfirm': 'Отозвать',
      'tokens.revoking': 'Отзыв…',
      'tokens.revokedNotice': 'Токен отозван. Запросы с ним теперь получают 401.',
      'tokens.created': 'Токен создан',
      'tokens.nameRequired': 'Укажите имя токена.',
      'tokens.scopesRequired': 'Выберите хотя бы одно разрешение.',
      'tokens.never': 'никогда',

      'bridge.title': 'Подключить мост',
      'bridge.hint': 'Добавьте этот блок в bridge.config.json рядом с настройками консоли. Мост отправляет захваченные документы в ingest-эндпоинт.',
      'bridge.url': 'Базовый URL runtime',
      'bridge.urlPlaceholder': 'https://runtime.example.de',
      'bridge.jsonTitle': 'forward.copilot',
      'bridge.dockerTitle': 'Команды Docker',
      'bridge.tokenHint': 'Задайте RUNTIME_INGEST_TOKEN в .env моста (права 600). Токену нужно разрешение documents:write.',
      'bridge.dockerHint': 'Замените тег образа на версию вашей установки. Команды выполняются на машине моста, а не на сервере runtime.',

      'once.badge': 'показывается один раз',
      'once.title': 'Скопируйте токен сейчас',
      'once.warning': 'Он не хранится в читаемом виде и больше не будет показан.',
      'once.saved': 'Я сохранил(а) его',

      'error.400': 'Запрос отклонён.',
      'error.401': 'Неверный admin-токен. Проверьте BC_RUNTIME_ADMIN_TOKEN в файле окружения установки.',
      'error.402': 'Лицензия не активна. Сначала установите действующий лицензионный ключ.',
      'error.403': 'Этому токену действие запрещено.',
      'error.404': 'API администрирования runtime не включён на этой установке (или установка уже настроена).',
      'error.409': 'Действие конфликтует с текущим состоянием.',
      'error.422': 'Введённые данные некорректны.',
      'error.429': 'Слишком много запросов. Подождите немного.',
      'error.500': 'Сервер не смог выполнить действие. Проверьте логи API по correlation id.',
      'error.network': 'Сервер не ответил. Проверьте, что API запущен и доступен.',
      'error.unexpected': 'Неожиданная ошибка.',

      'footer.note': 'Администрирование Robots Runtime. Страница работает локально на вашем сервере; данные покидают установку только если вы включите внешний канал.'
    }
  };

  /* ----------------------------------------------------------------- storage */

  const session = {
    get(key) {
      try { return window.sessionStorage.getItem(key); } catch (error) { return null; }
    },
    set(key, value) {
      try { window.sessionStorage.setItem(key, value); } catch (error) { /* private mode */ }
    },
    remove(key) {
      try { window.sessionStorage.removeItem(key); } catch (error) { /* private mode */ }
    }
  };

  const local = {
    get(key) {
      try { return window.localStorage.getItem(key); } catch (error) { return null; }
    },
    set(key, value) {
      try { window.localStorage.setItem(key, value); } catch (error) { /* private mode */ }
    }
  };

  const state = {
    lang: detectLanguage(),
    token: session.get(TOKEN_KEY) || '',
    connected: false,
    authInvalid: false,
    status: null,
    health: null,
    tokens: [],
    scopes: [],
    scopesRendered: false,
    busy: false
  };

  let revokeTarget = null;

  /* --------------------------------------------------------------- utilities */

  const byId = (id) => document.getElementById(id);

  function detectLanguage() {
    const stored = local.get(LANG_KEY);
    if (LANGUAGES.includes(stored)) {
      return stored;
    }
    const preferred = Array.isArray(navigator.languages) ? navigator.languages : [navigator.language];
    for (const candidate of preferred) {
      const base = String(candidate || '').toLowerCase().split('-')[0];
      if (LANGUAGES.includes(base)) {
        return base;
      }
    }
    return 'en';
  }

  function hasKey(key) {
    return Object.prototype.hasOwnProperty.call(I18N[state.lang] || {}, key);
  }

  function t(key, vars) {
    const table = I18N[state.lang] || I18N.en;
    let text = Object.prototype.hasOwnProperty.call(table, key) ? table[key] : I18N.en[key];
    if (text === undefined) {
      return key;
    }
    if (vars) {
      Object.keys(vars).forEach((name) => {
        text = text.replace(new RegExp('\\{' + name + '\\}', 'g'), String(vars[name]));
      });
    }
    return text;
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined && text !== null) {
      node.textContent = String(text);
    }
    return node;
  }

  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  function formatDate(value) {
    if (!value) {
      return t('common.notAvailable');
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return String(value);
    }
    try {
      return new Intl.DateTimeFormat(state.lang, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
    } catch (error) {
      return date.toISOString();
    }
  }

  /* --------------------------------------------------------------------- api */

  class ApiFailure extends Error {
    constructor(status, body, network) {
      super('API request failed');
      this.name = 'ApiFailure';
      this.status = status;
      this.body = body;
      this.network = Boolean(network);
    }
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');
    if (state.token) {
      headers.set('X-Runtime-Admin-Token', state.token);
    }
    if (options.body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    let response;
    try {
      response = await fetch(path, {
        method: options.method || 'GET',
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        cache: 'no-store',
        credentials: 'omit'
      });
    } catch (error) {
      throw new ApiFailure(0, null, true);
    }
    const text = await response.text();
    let body = null;
    if (text) {
      try {
        body = JSON.parse(text);
      } catch (error) {
        body = null;
      }
    }
    if (!response.ok) {
      throw new ApiFailure(response.status, body, false);
    }
    return body;
  }

  function statusKey(status) {
    const known = {
      400: 'error.400',
      401: 'error.401',
      402: 'error.402',
      403: 'error.403',
      404: 'error.404',
      409: 'error.409',
      422: 'error.422',
      429: 'error.429'
    };
    if (status === 0) {
      return 'error.network';
    }
    return known[status] ? known[status] : 'error.500';
  }

  function firstViolation(body) {
    if (!body || !Array.isArray(body.details) || body.details.length === 0) {
      return null;
    }
    return body.details[0];
  }

  function describeFailure(failure, context) {
    if (!(failure instanceof ApiFailure)) {
      return { title: t('error.unexpected'), detail: '' };
    }
    const violation = firstViolation(failure.body);
    if (context === 'license' && violation && violation.code && hasKey('license.reason.' + violation.code)) {
      return { title: t('license.reason.' + violation.code), detail: violation.message || '' };
    }
    return {
      title: t(statusKey(failure.status)),
      detail: (violation && violation.message) || (failure.body && failure.body.message) || ''
    };
  }

  /* ------------------------------------------------------------------ banner */

  function showBanner(text, kind = 'error', detail = '') {
    const banner = byId('banner');
    clear(banner);
    banner.hidden = false;
    banner.className = 'banner' + (kind === 'success' ? ' success' : '');
    const title = el('span', 'banner-title', text);
    banner.appendChild(title);
    if (detail) {
      banner.appendChild(el('span', 'banner-detail', detail));
    }
  }

  function clearBanner() {
    const banner = byId('banner');
    banner.hidden = true;
    clear(banner);
  }

  /* -------------------------------------------------------------------- auth */

  function setAuthBadge(connected) {
    const badge = byId('auth-badge');
    if (connected) {
      badge.className = 'badge badge-success';
      badge.textContent = t('auth.connected');
    } else if (state.authInvalid) {
      badge.className = 'badge badge-danger';
      badge.textContent = t('auth.invalid');
    } else {
      badge.className = 'badge badge-neutral';
      badge.textContent = t('auth.notChecked');
    }
    byId('auth-forget').hidden = !connected;
    byId('auth-check').textContent = state.busy ? t('auth.checking') : t('auth.check');
  }

  async function connect() {
    const input = byId('admin-token');
    const token = input.value.trim();
    if (!token) {
      showBanner(t('auth.tokenRequired'));
      input.focus();
      return;
    }
    state.token = token;
    session.set(TOKEN_KEY, token);
    state.authInvalid = false;
    state.busy = true;
    setAuthBadge(state.connected);
    try {
      await refreshAll();
    } finally {
      state.busy = false;
      setAuthBadge(state.connected);
    }
  }

  async function refreshAll() {
    try {
      state.status = await api('/api/v1/runtime/status');
      state.connected = true;
      state.authInvalid = false;
      session.set(TOKEN_KEY, state.token);
      byId('dashboard').hidden = false;
      clearBanner();
      await Promise.all([loadHealth(), loadTokens()]);
      renderAll();
    } catch (failure) {
      state.connected = false;
      byId('dashboard').hidden = true;
      if (failure instanceof ApiFailure && failure.status === 401) {
        session.remove(TOKEN_KEY);
        state.token = '';
        state.authInvalid = true;
      }
      const info = describeFailure(failure);
      showBanner(info.title, 'error', info.detail);
      setAuthBadge(false);
      throw failure;
    }
  }

  async function loadHealth() {
    state.health = 'UNKNOWN';
    try {
      const response = await fetch('/actuator/health', {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'omit'
      });
      let body = null;
      try {
        body = await response.json();
      } catch (error) {
        body = null;
      }
      state.health = body && body.status ? String(body.status) : (response.ok ? 'UNKNOWN' : 'DOWN');
    } catch (error) {
      state.health = 'UNREACHABLE';
    }
  }

  async function loadTokens() {
    if (!state.status || !state.status.bootstrapped) {
      state.tokens = [];
      state.scopes = [];
      return;
    }
    try {
      const result = await api('/api/v1/runtime/tokens');
      state.tokens = Array.isArray(result.items) ? result.items : [];
      state.scopes = Array.isArray(result.availableScopes) ? result.availableScopes : [];
      byId('tokens-error').hidden = true;
      renderScopes();
    } catch (failure) {
      state.tokens = [];
      const info = describeFailure(failure);
      const error = byId('tokens-error');
      error.hidden = false;
      error.textContent = info.title + (info.detail ? ' ' + info.detail : '');
    }
  }

  function forgetToken() {
    session.remove(TOKEN_KEY);
    state.token = '';
    state.connected = false;
    state.authInvalid = false;
    state.status = null;
    state.tokens = [];
    byId('admin-token').value = '';
    byId('dashboard').hidden = true;
    clearBanner();
    showBanner(t('auth.forgotten'), 'success');
    setAuthBadge(false);
  }

  /* ---------------------------------------------------------------- rendering */

  function renderAll() {
    renderStatus();
    renderBootstrapState();
    renderTokens();
    renderBridge();
  }

  function healthLabel() {
    switch (state.health) {
      case 'UP':
        return t('status.healthUp');
      case 'DOWN':
      case 'OUT_OF_SERVICE':
        return t('status.healthDown');
      case 'UNREACHABLE':
        return t('status.healthUnreachable');
      default:
        return t('status.healthUnknown');
    }
  }

  function licenseStateKey(licenseState) {
    return 'license.state.' + (licenseState && hasKey('license.state.' + licenseState)
      ? licenseState : 'LICENSE_UNKNOWN');
  }

  function licenseBadgeClass(licenseState) {
    switch (licenseState) {
      case 'LICENSE_ACTIVE':
        return 'badge-success';
      case 'LICENSE_GRACE':
      case 'LICENSE_READ_ONLY':
        return 'badge-warn';
      case 'LICENSE_REVOKED':
      case 'LICENSE_MISSING':
        return 'badge-danger';
      default:
        return 'badge-neutral';
    }
  }

  function renderStatus() {
    const grid = byId('status-grid');
    const summary = byId('license-summary');
    clear(grid);
    clear(summary);
    if (!state.status) {
      return;
    }
    const status = state.status;
    const license = status.license || {};
    const rows = [
      [t('status.version'), status.version || t('common.notAvailable')],
      [t('status.instanceId'), license.instanceId || t('common.notAvailable')],
      [t('status.health'), healthLabel()],
      [t('status.bootstrapped'), status.bootstrapped ? t('common.yes') : t('common.no')],
      [t('status.tenantId'), status.tenantId || t('common.notAvailable')],
      [t('status.workspaceId'), status.workspaceId || t('common.notAvailable')],
      [t('status.ownerEmail'), status.ownerEmail || t('common.notAvailable')],
      [t('status.activeTokens'), status.activeTokens === null || status.activeTokens === undefined
        ? t('common.notAvailable') : String(status.activeTokens)]
    ];
    rows.forEach(([label, value]) => {
      const box = el('div', 'stat');
      box.appendChild(el('span', 'stat-key', label));
      box.appendChild(el('span', 'stat-value', value));
      grid.appendChild(box);
    });

    const badge = el('span', 'badge ' + licenseBadgeClass(license.state), t(licenseStateKey(license.state)));
    summary.appendChild(badge);
    if (license.plan) {
      summary.appendChild(el('strong', null, license.plan));
    }
    if (license.customer) {
      summary.appendChild(el('span', 'detail', t('license.customer') + ': ' + license.customer));
    }
    if (license.perpetual) {
      summary.appendChild(el('span', 'detail', t('license.perpetual')));
    } else if (license.expiresAt) {
      let text = t('license.expires') + ': ' + formatDate(license.expiresAt);
      if (typeof license.daysRemaining === 'number') {
        const days = license.daysRemaining;
        if (days < 0) {
          text += ' · ' + t('license.expired');
        } else if (license.state === 'LICENSE_GRACE' && typeof license.graceDaysRemaining === 'number') {
          text += ' · ' + t('license.graceDaysLeft', { days: license.graceDaysRemaining });
        } else {
          text += ' · ' + t('license.daysLeft', { days });
        }
      }
      summary.appendChild(el('span', 'detail', text));
    }
    if (license.revocationReason) {
      summary.appendChild(el('span', 'detail', license.revocationReason));
    } else if (license.reason) {
      summary.appendChild(el('span', 'detail', license.reason));
    }
  }

  function renderBootstrapState() {
    const bootstrapped = Boolean(state.status && state.status.bootstrapped);
    byId('bootstrap-form').hidden = bootstrapped;
    byId('bootstrap-done').hidden = !bootstrapped;
    byId('tokens-requires-bootstrap').hidden = bootstrapped || !state.connected;
    byId('token-form').hidden = !bootstrapped;

    const kv = byId('bootstrap-done-kv');
    clear(kv);
    if (bootstrapped) {
      const pairs = [
        [t('status.tenantId'), state.status.tenantId],
        [t('status.workspaceId'), state.status.workspaceId],
        [t('status.ownerEmail'), state.status.ownerEmail]
      ];
      pairs.forEach(([label, value]) => {
        if (value) {
          kv.appendChild(el('dt', null, label));
          kv.appendChild(el('dd', null, value));
        }
      });
    }
  }

  function renderScopes() {
    const container = byId('scopes');
    if (state.scopesRendered) {
      return;
    }
    clear(container);
    const scopes = state.scopes.length ? state.scopes : DEFAULT_SCOPES;
    scopes.forEach((scope) => {
      const label = el('label', 'scope');
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.name = 'scope';
      input.value = scope;
      input.checked = DEFAULT_SCOPES.includes(scope);
      label.appendChild(input);
      label.appendChild(document.createTextNode(scope));
      container.appendChild(label);
    });
    state.scopesRendered = true;
  }

  function renderTokens() {
    const body = byId('tokens-body');
    const table = byId('tokens-table');
    const empty = byId('tokens-empty');
    clear(body);
    const bootstrapped = Boolean(state.status && state.status.bootstrapped);
    if (!bootstrapped) {
      table.hidden = true;
      empty.hidden = true;
      return;
    }
    if (!state.tokens.length) {
      table.hidden = true;
      empty.hidden = false;
      empty.textContent = t('tokens.empty');
      return;
    }
    table.hidden = false;
    empty.hidden = true;
    state.tokens.forEach((token) => body.appendChild(tokenRow(token)));
  }

  function cell(label, text) {
    const td = document.createElement('td');
    td.dataset.label = label;
    if (text instanceof Node) {
      td.appendChild(text);
    } else {
      td.textContent = text === undefined || text === null ? t('common.notAvailable') : String(text);
    }
    return td;
  }

  function tokenRow(token) {
    const row = document.createElement('tr');

    const nameCell = document.createElement('td');
    nameCell.dataset.label = t('tokens.colName');
    // One element for name and prefix: on a narrow screen the row turns into a flex line
    // (label left, value right), and two separate spans would scatter the value.
    const identity = el('span', 'token-identity');
    identity.appendChild(el('span', 'token-name', token.name || t('common.notAvailable')));
    if (token.prefix) {
      identity.appendChild(el('span', 'prefix', token.prefix + '…'));
    }
    nameCell.appendChild(identity);
    row.appendChild(nameCell);

    const scopesCell = document.createElement('td');
    scopesCell.dataset.label = t('tokens.colScopes');
    (Array.isArray(token.scopes) ? token.scopes : []).forEach((scope) => {
      scopesCell.appendChild(el('span', 'scope-chip', scope));
    });
    row.appendChild(scopesCell);

    row.appendChild(cell(t('tokens.colCreated'), formatDate(token.createdAt)));
    row.appendChild(cell(t('tokens.colLastUsed'), token.lastUsedAt ? formatDate(token.lastUsedAt) : t('tokens.never')));

    const statusCell = document.createElement('td');
    statusCell.dataset.label = t('tokens.colStatus');
    statusCell.appendChild(el('span', 'badge ' + (token.active ? 'badge-success' : 'badge-neutral'),
      token.active ? t('tokens.active') : t('tokens.revoked')));
    row.appendChild(statusCell);

    const actionCell = document.createElement('td');
    actionCell.dataset.label = '';
    actionCell.className = 'cell-actions';
    const button = el('button', 'btn small ghost', t('tokens.revoke'));
    button.type = 'button';
    button.disabled = !token.active;
    button.addEventListener('click', () => askRevoke(token));
    actionCell.appendChild(button);
    row.appendChild(actionCell);

    return row;
  }

  function renderBridge() {
    const input = byId('bridge-url');
    if (!input.value) {
      input.value = local.get(BRIDGE_URL_KEY) || window.location.origin;
    }
    const url = (input.value.trim() || window.location.origin).replace(/\/+$/, '');
    const config = {
      forward: {
        copilot: {
          enabled: true,
          url,
          token: '${RUNTIME_INGEST_TOKEN}',
          timeoutMs: 30000
        }
      }
    };
    byId('bridge-json').textContent = JSON.stringify(config, null, 2);
    byId('bridge-docker').textContent = [
      'docker run --rm -it -v "$PWD:/data" -w /data firmspace-bridge:0.1.0 \\',
      '  import --from bridge.config.json --force',
      '',
      'docker run --rm -it -v "$PWD:/data" -w /data firmspace-bridge:0.1.0 test',
      'docker run --rm -it -v "$PWD:/data" -w /data firmspace-bridge:0.1.0 doctor',
      '',
      'docker run -d --name fs-bridge --restart unless-stopped \\',
      '  -v "$PWD:/data" -w /data firmspace-bridge:0.1.0 run'
    ].join('\n');
  }

  /* ------------------------------------------------------------ one-time token */

  function showOneTime(container, token, titleKey) {
    container._token = token;
    container._titleKey = titleKey || 'once.title';
    container.hidden = false;
    renderOneTime(container);
  }

  function renderOneTime(container) {
    const token = container._token;
    if (!token) {
      container.hidden = true;
      return;
    }
    container.hidden = false;
    clear(container);
    const head = el('div', 'once-title');
    head.appendChild(el('span', 'badge badge-warn', t('once.badge')));
    head.appendChild(document.createTextNode(t(container._titleKey)));
    container.appendChild(head);
    container.appendChild(el('p', null, t('once.warning')));

    const row = el('div', 'copy-row');
    row.appendChild(el('code', null, token));
    const copy = el('button', 'btn small', t('common.copy'));
    copy.type = 'button';
    copy.addEventListener('click', () => copyText(token, copy));
    row.appendChild(copy);
    container.appendChild(row);

    const dismiss = el('button', 'btn small ghost', t('once.saved'));
    dismiss.type = 'button';
    dismiss.addEventListener('click', () => {
      container._token = null;
      container.replaceChildren();
      container.hidden = true;
    });
    container.appendChild(dismiss);
  }

  async function copyText(text, button) {
    const original = button.textContent;
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
      } else {
        const helper = document.createElement('textarea');
        helper.value = text;
        helper.setAttribute('readonly', '');
        helper.style.position = 'fixed';
        helper.style.top = '-1000px';
        document.body.appendChild(helper);
        helper.select();
        const copied = document.execCommand('copy');
        document.body.removeChild(helper);
        if (!copied) {
          throw new Error('copy command failed');
        }
      }
      button.textContent = t('common.copied');
    } catch (error) {
      button.textContent = t('common.copyFailed');
    }
    window.setTimeout(() => {
      button.textContent = original;
    }, 2000);
  }

  /* ------------------------------------------------------------------ actions */

  async function submitLicense(event) {
    event.preventDefault();
    const textarea = byId('license-key');
    const key = textarea.value.trim();
    const button = byId('license-install');
    if (!key) {
      showLicenseResult('error', t('license.empty'));
      textarea.focus();
      return;
    }
    button.disabled = true;
    button.textContent = t('license.installing');
    try {
      const result = await api('/api/v1/runtime/license', { method: 'POST', body: { licenseKey: key } });
      textarea.value = '';
      state.status = await api('/api/v1/runtime/status');
      await loadTokens();
      renderAll();
      const license = result.license || {};
      const lines = [];
      const stateKey = result.state || license.state;
      if (stateKey && hasKey('license.state.' + stateKey)) {
        lines.push(t('license.title') + ': ' + t('license.state.' + stateKey));
      }
      if (license.perpetual) {
        lines.push(t('license.perpetual'));
      } else if (license.expiresAt) {
        let term = t('license.expires') + ': ' + formatDate(license.expiresAt);
        if (typeof license.daysRemaining === 'number' && license.daysRemaining >= 0) {
          term += ' · ' + t('license.daysLeft', { days: license.daysRemaining });
        }
        lines.push(term);
      }
      if (result.keyFile) {
        lines.push(t('license.file') + ': ' + result.keyFile);
      }
      (Array.isArray(result.warnings) ? result.warnings : []).forEach((warning) => {
        const text = warning && warning.code && hasKey('license.warn.' + warning.code)
          ? t('license.warn.' + warning.code)
          : (warning && warning.message ? warning.message : '');
        if (text) {
          lines.push(text);
        }
      });
      showLicenseResult('success', t('license.installed'), lines);
    } catch (failure) {
      const info = describeFailure(failure, 'license');
      showLicenseResult('error', info.title, info.detail ? [info.detail] : []);
    } finally {
      button.disabled = false;
      button.textContent = t('license.install');
    }
  }

  function showLicenseResult(kind, title, lines = []) {
    const box = byId('license-result');
    box.hidden = false;
    box.className = 'result ' + (kind === 'success' ? 'is-success' : 'is-error');
    clear(box);
    const head = el('div', 'result-head');
    if (kind === 'success') {
      // The badge already says "installed"; repeating it as a title reads like a stutter.
      head.appendChild(el('span', 'badge badge-success', t('license.installed')));
    } else {
      head.appendChild(document.createTextNode(title));
    }
    box.appendChild(head);
    if (kind === 'success') {
      box.appendChild(el('p', 'server-message', t('license.installedHint')));
    }
    const usable = lines.filter((line) => line);
    if (kind === 'success' && usable.length) {
      const list = document.createElement('ul');
      usable.forEach((line) => list.appendChild(el('li', null, line)));
      box.appendChild(list);
    } else if (kind !== 'success') {
      // The server's sentence is the technical detail under the localized reason; keep it
      // readable but visibly secondary.
      usable.forEach((line) => box.appendChild(el('p', 'server-message', line)));
    }
  }

  async function submitBootstrap(event) {
    event.preventDefault();
    const button = byId('bootstrap-submit');
    button.disabled = true;
    button.textContent = t('bootstrap.submitting');
    try {
      const result = await api('/api/v1/runtime/bootstrap', {
        method: 'POST',
        body: {
          tenantName: byId('bootstrap-tenant').value.trim(),
          ownerEmail: byId('bootstrap-email').value.trim(),
          ownerName: byId('bootstrap-name').value.trim() || null
        }
      });
      state.status = await api('/api/v1/runtime/status');
      await loadTokens();
      renderAll();
      renderBootstrapResult(result);
      byId('bootstrap-form').reset();
    } catch (failure) {
      const info = describeFailure(failure);
      const box = byId('bootstrap-result');
      box.hidden = false;
      box.className = 'result is-error';
      clear(box);
      const head = el('div', 'result-head', info.title);
      box.appendChild(head);
      if (failure instanceof ApiFailure && failure.status === 409) {
        box.appendChild(el('p', 'server-message', t('bootstrap.alreadyExists')));
      } else if (info.detail) {
        box.appendChild(el('p', 'server-message', info.detail));
      }
    } finally {
      button.disabled = false;
      button.textContent = t('bootstrap.submit');
    }
  }

  function renderBootstrapResult(result) {
    const box = byId('bootstrap-result');
    box.hidden = false;
    box.className = 'result is-success';
    clear(box);
    box.appendChild(el('div', 'result-head', t('bootstrap.resultTitle')));
    const kv = el('dl', 'kv');
    [
      [t('status.tenantId'), result.tenantId],
      [t('status.workspaceId'), result.workspaceId],
      [t('status.ownerEmail'), result.ownerEmail]
    ].forEach(([label, value]) => {
      if (value) {
        kv.appendChild(el('dt', null, label));
        kv.appendChild(el('dd', null, value));
      }
    });
    box.appendChild(kv);
    showOneTime(byId('bootstrap-once'), result.token, 'bootstrap.resultTitle');
  }

  async function submitToken(event) {
    event.preventDefault();
    const name = byId('token-name').value.trim();
    const scopes = Array.from(document.querySelectorAll('input[name="scope"]:checked'))
      .map((input) => input.value);
    const errorBox = byId('tokens-error');
    if (!name) {
      errorBox.hidden = false;
      errorBox.textContent = t('tokens.nameRequired');
      byId('token-name').focus();
      return;
    }
    if (!scopes.length) {
      errorBox.hidden = false;
      errorBox.textContent = t('tokens.scopesRequired');
      return;
    }
    const button = byId('token-create');
    button.disabled = true;
    button.textContent = t('tokens.creating');
    try {
      const result = await api('/api/v1/runtime/tokens', {
        method: 'POST',
        body: { name, scopes }
      });
      byId('token-name').value = '';
      errorBox.hidden = true;
      await loadTokens();
      renderTokens();
      showOneTime(byId('token-once'), result.token, 'tokens.created');
    } catch (failure) {
      const info = describeFailure(failure);
      errorBox.hidden = false;
      errorBox.textContent = info.title + (info.detail ? ' ' + info.detail : '');
    } finally {
      button.disabled = false;
      button.textContent = t('tokens.create');
    }
  }

  function askRevoke(token) {
    revokeTarget = token;
    byId('revoke-name').textContent = (token.name || '') + (token.prefix ? ' (' + token.prefix + '…)' : '');
    const dialog = byId('revoke-dialog');
    if (typeof dialog.showModal === 'function') {
      dialog.showModal();
    } else {
      dialog.setAttribute('open', '');
    }
  }

  async function confirmRevoke() {
    if (!revokeTarget) {
      return;
    }
    const button = byId('revoke-confirm');
    button.disabled = true;
    button.textContent = t('tokens.revoking');
    try {
      await api('/api/v1/runtime/tokens/' + encodeURIComponent(revokeTarget.id) + '/revoke', {
        method: 'POST'
      });
      byId('revoke-dialog').close();
      revokeTarget = null;
      await loadTokens();
      renderTokens();
      showBanner(t('tokens.revokedNotice'), 'success');
    } catch (failure) {
      const info = describeFailure(failure);
      showBanner(info.title, 'error', info.detail);
    } finally {
      button.disabled = false;
      button.textContent = t('tokens.revokeConfirm');
    }
  }

  /* ------------------------------------------------------------------ language */

  function setLanguage(lang) {
    if (!LANGUAGES.includes(lang)) {
      return;
    }
    state.lang = lang;
    local.set(LANG_KEY, lang);
    applyLanguage();
  }

  function applyLanguage() {
    document.documentElement.lang = state.lang;
    document.querySelectorAll('[data-i18n]').forEach((node) => {
      node.textContent = t(node.dataset.i18n);
    });
    document.querySelectorAll('[data-i18n-placeholder]').forEach((node) => {
      node.setAttribute('placeholder', t(node.dataset.i18nPlaceholder));
    });
    document.querySelectorAll('[data-i18n-aria-label]').forEach((node) => {
      node.setAttribute('aria-label', t(node.dataset.i18nAriaLabel));
    });
    document.querySelectorAll('.lang').forEach((button) => {
      const active = button.dataset.lang === state.lang;
      button.setAttribute('aria-pressed', String(active));
    });
    document.querySelectorAll('.once').forEach((container) => {
      if (container._token) {
        renderOneTime(container);
      }
    });
    setAuthBadge(state.connected);
    if (state.status) {
      renderAll();
    } else {
      renderBridge();
    }
  }

  /* -------------------------------------------------------------------- wiring */

  function wire() {
    byId('auth-form').addEventListener('submit', (event) => {
      event.preventDefault();
      connect().catch(() => { /* refreshAll already showed the banner */ });
    });
    byId('auth-forget').addEventListener('click', forgetToken);
    byId('refresh').addEventListener('click', () => {
      state.busy = true;
      setAuthBadge(state.connected);
      refreshAll()
        .catch(() => { /* the banner already explains it */ })
        .finally(() => {
          state.busy = false;
          setAuthBadge(state.connected);
        });
    });
    byId('license-form').addEventListener('submit', submitLicense);
    byId('bootstrap-form').addEventListener('submit', submitBootstrap);
    byId('token-form').addEventListener('submit', submitToken);
    byId('revoke-cancel').addEventListener('click', () => byId('revoke-dialog').close());
    byId('revoke-confirm').addEventListener('click', confirmRevoke);
    document.querySelectorAll('.lang').forEach((button) => {
      button.addEventListener('click', () => setLanguage(button.dataset.lang));
    });
    document.querySelectorAll('[data-copy-target]').forEach((button) => {
      button.addEventListener('click', () => {
        const target = byId(button.dataset.copyTarget);
        if (target) {
          copyText(target.textContent, button);
        }
      });
    });
    byId('bridge-url').addEventListener('input', () => {
      local.set(BRIDGE_URL_KEY, byId('bridge-url').value.trim());
      renderBridge();
    });
  }

  function start() {
    applyLanguage();
    wire();
    if (state.token) {
      const input = byId('admin-token');
      input.value = state.token;
      connect().catch(() => { /* banner is shown by refreshAll */ });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
