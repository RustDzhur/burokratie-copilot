# Bürokratie-Copilot — Web-Frontend (`apps/web`)

Next.js 15 (App Router) · React 19 · TypeScript strict · pnpm · CSS Custom Properties

Dieses Paket ist das vollständige Web-Frontend der Plattform: Design-System,
App-Shell, Lokalisierung (de/en/ru), typisierter API-Client und alle 30 in
`docs/task.md` §5.2 geforderten Bildschirme.

---

## 1. Schnellstart

```bash
cd apps/web
pnpm install
cp .env.example .env.local     # optional: API-Basis-URL anpassen
pnpm dev                       # http://localhost:3000
```

Weitere Skripte:

| Befehl               | Zweck                                                    |
| -------------------- | -------------------------------------------------------- |
| `pnpm dev`           | Entwicklungsserver (App Router, Fast Refresh)            |
| `pnpm build`         | Produktions-Build                                        |
| `pnpm start`         | Produktionsserver (nach `pnpm build`)                    |
| `pnpm lint`          | ESLint 9 (flat config) inkl. `jsx-a11y` und `@next/next` |
| `pnpm typecheck`     | `tsc --noEmit`, strict, ohne `any`                       |
| `pnpm test`          | Vitest + Testing Library + jest-axe (einmalig)           |
| `pnpm test:watch`    | Vitest im Watch-Modus                                    |
| `pnpm test:coverage` | Vitest mit V8-Coverage                                   |
| `pnpm format`        | Prettier (Schreibmodus)                                  |
| `pnpm format:check`  | Prettier-Prüfung                                         |

Umgebungsvariablen (siehe `.env.example`):

- `NEXT_PUBLIC_API_BASE_URL` — Basis-URL des Backends, Standard `/api/v1`.
- `NEXT_PUBLIC_API_TIMEOUT_MS` — Timeout des API-Clients, Standard `20000`.
- `NEXT_PUBLIC_DEFAULT_LOCALE` — Server-Fallback-Locale (`de`).

---

## 2. Architektur

```
src/
  app/                    Routen (App Router)
    (public)/             Landingpage (eigenes Layout)
    (auth)/               login, register, verify-email, reset-password
    (app)/                Alle Arbeitsbereichs-Screens in der App-Shell
    setup/                Ersteinrichtung (drei Schritte, eigenes Layout)
    design-system/        Dev-Vorschau aller Primitives (/_design leitet hierher)
    not-found.tsx, error.tsx
  components/
    ui/                   Design-System (Button, Dialog, DataTable, …) + CSS Module
    app/                  Shell, Sidebar, Topbar, ListPage, ResourceView, …
    icons.tsx             Abhängigkeitsfreies Icon-Set
  i18n/                   Kataloge (de/en/ru), ICU-Teilmenge, Formatierung, Provider
  lib/
    api/                  Typisierter Client, Fehlermodell, Endpunkte, Hooks
    format/status.ts      Status → Ton/Label (ohne Farb-only-Semantik)
  navigation/screens.ts   Registry aller 30 Screens (Sidebar, Suche, Tests)
  settings/appearance.ts  Theme, Kontrast, Textgröße, Motion, Muster, Dichte
  design-system/          Farbmathematik + CSS-Token-Parser (für Tests/Tooling)
  styles/                 tokens.css, globals.css
```

### Zustandsmodell der Screens

Jeder datengetriebene Screen nutzt `useApiResource` + `ResourceView` und rendert
damit **immer** vier Zustände (Ladezustand, Fehler, leer, Inhalt). Das ist die
Umsetzung von §29 („kein leerer Bildschirm“). `ListPage` bündelt zusätzlich
Suche, Filter, Sortierung, serverseitige Pagination und einen
Anlegen-Dialog, damit alle Listenscreens identisch funktionieren.

### Fehlerverhalten ohne Backend

Ist das Backend nicht erreichbar, zeigt jeder Screen den Fehlerzustand mit
lokalisierter Meldung und Korrelations-ID – **es werden keine Ersatzdaten
erfunden** (§1.2). Einzige Ausnahme sind die klar gekennzeichneten
Beispieldaten auf der öffentlichen Landingpage.

---

## 3. Design-System

- Alle visuellen Werte stammen aus `src/styles/tokens.css` (Farbe, Typografie,
  Abstände, Radien, Schatten, Motion, z-index).
- Themes: `[data-theme='dark']`, `[data-contrast='high']`,
  `[data-text-scale='112'|'125']`, `[data-motion='reduced']`,
  `[data-effects='reduced']`, `[data-color-cues='patterns']`,
  `[data-density='comfortable'|'compact']`.
- Die Einstellungen werden vor dem ersten Paint per Inline-Skript angewandt
  (`appearanceBootstrapScript`) und in Cookie + localStorage gespeichert.
- Kontrast: `src/design-system/__tests__/contrast.test.ts` liest die
  ausgelieferten Tokens und prüft für hell, dunkel und beide
  Hochkontrast-Varianten alle Text/Hintergrund-Paare auf ≥ 4,5:1 sowie
  Bedienelement-Grenzen auf ≥ 3:1 (WCAG 2.2 AA / 1.4.11).
- Status wird nie nur über Farbe transportiert: `StatusPill` und `Badge` führen
  immer Text, im Muster-Modus zusätzlich Formmerkmale.

**Dev-Vorschau:** `/design-system` (Alias `/_design`) rendert jedes Primitive in
jedem Zustand. In Produktions-Builds liefert die Route `notFound()`.

---

## 4. Lokalisierung

- Kataloge: `src/i18n/messages/{core,screens}/{de,en,ru}.ts`.
- `Messages` wird aus dem **deutschen** Katalog abgeleitet; `en`/`ru` sind mit
  diesem Typ annotiert. Ein fehlender Schlüssel ist damit ein Compile-Fehler.
  Ein Test vergleicht zusätzlich alle Namespaces Schlüssel für Schlüssel.
- ICU-Teilmenge in `src/i18n/icu.ts`: Interpolation, `plural`
  (inkl. `=0`), `select`, `number`, `currency:EUR`, `date`, `time`.
  Russische Pluralformen nutzen die vollen CLDR-Kategorien (one/few/many/other).
- Formatierung: `de-DE` Datum `dd.MM.yyyy`, Währung `1.234,56 €`,
  Zeitzone `Europe/Berlin` (`src/i18n/format.ts`).
- Screen-Titel liegen einmalig in `nav.items.*` und werden von Sidebar,
  Breadcrumbs, Befehlspalette und Seitenkopf gemeinsam genutzt.

---

## 5. API-Client

`src/lib/api/`:

- `client.ts` — fetch-Wrapper mit Basis-URL, Timeout/`AbortSignal`,
  `Idempotency-Key` (UUID v4 bei `idempotencyKey: true`),
  Upload per XHR inklusive Fortschritt und Abbruch,
  Normalisierung der Pagination `{ items, page, size, totalItems, totalPages }`.
- `errors.ts` — `ApiError` mit `kind` (`network`, `timeout`, `aborted`,
  `unauthorized`, `forbidden`, `notFound`, `conflict`, `validation`,
  `rateLimited`, `server`, `malformed`, `unknown`), `correlationId`,
  `fieldErrors`, `retryAfterSeconds`, `isRetryable`.
- `resources.ts` — alle Endpunkte nach den Gruppen aus §20.
- `hooks.ts` — `useApiResource` (Zustandsmaschine) und `useAsyncAction`.

**Vertragsannahme:** In `docs/task.md` sind für die meisten Ressourcen nur die
DB-Modelle (§19) und die Endpunktgruppen (§20) definiert. Die DTOs in
`src/lib/api/types.ts` sind daher ein **Vorschlag** (camelCase-Felder,
ISO-8601-Zeitstempel, Spring-Data-kompatible Pagination `page`/`size`/`sort`,
Freitextsuche `q`). Sobald OpenAPI aus dem Backend vorliegt, können die DTOs
(und optional die Endpunktpfade) daraus generiert werden, ohne Komponenten
anzufassen.

---

## 6. Tests

```bash
pnpm test          # 148 Tests, 13 Dateien
pnpm test:coverage
```

Abgedeckt sind: Design-System-Primitives (Rendering, Tastatur, deaktiviert,
Ladezustand), Formular-A11y-Verknüpfungen, Dialoge/Drawer (Fokusfalle, Escape,
`aria-modal`), Tabellen (Sortierung, leerer Zustand), Tabs, Toasts
(Live-Region-Rollen), Datei-Dropzone (Typ-/Größengrenzen, Abbruch),
Befehlspalette, API-Client (Fehler-Mapping, Idempotenz-Header, Timeout, Abbruch),
i18n (Sprachwechsel, Plural, Formatierung, Katalog-Parität), Navigations-Registry,
axe-Prüfungen für App-Shell und zentrale Screens sowie der Kontrast-Test der
ausgelieferten Tokens.

---

## 7. Bewusste Einschränkungen (Stand dieses Fundaments)

1. **Design-Vorschau** enthält deutsche Beispieltexte (Sample-Inhalte einer
   Entwicklerseite, keine Produkttexte). Produktoberflächen sind vollständig
   lokalisiert.
2. **Landingpage-Zahlen** sind Beispieldaten und als solche gekennzeichnet.
3. **Konversationsverlauf des KI-Assistenten** lebt nur im Browser-Speicher;
   eine serverseitige Historie ist im API-Vertrag nicht vorgesehen.
4. **Dokumentenvorschau** nutzt ein `<iframe>` auf `/documents/{id}/content`;
   abgesicherte, tokenisierte Preview-URLs kommen mit dem Backend.
5. **Formatierte Vorlagen** (Rich-Text/Layout) sind noch nicht umgesetzt: der
   Vorlagen-Editor bearbeitet Klartext mit Platzhaltern (im UI benannt).
6. **Kein CSP-Nonce-Setup**: Sicherheits-Header sind gesetzt, eine strikte CSP
   benötigt Middleware bzw. eine Abstimmung mit dem Backend (§30).
7. **E2E-Tests (Playwright)** gehören laut §28.2 zum Gesamtumfang, sind aber
   nicht Teil dieses Frontend-Fundaments.
8. **Offline/PWA** (Push-Kanal, Service Worker) ist nicht implementiert; im
   Benachrichtigungszentrum ist der Kanal „Push“ als Backend-Fähigkeit
   beschrieben.
