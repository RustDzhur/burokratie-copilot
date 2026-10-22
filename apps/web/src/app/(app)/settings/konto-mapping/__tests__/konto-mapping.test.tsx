import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement, ReactNode } from 'react';
import {
  expectNoA11yViolations,
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
  within,
} from '@/test/render';
import { SessionProvider } from '@/lib/auth/session-context';
import type { KontoMapping, KontoMappingImportReport, SessionUser } from '@/lib/api/types';
import KontoMappingPage from '../page';

const mappings: readonly KontoMapping[] = [
  {
    id: 'map-1',
    mandantId: 'contact-1',
    mandantName: 'Mandant A GmbH',
    supplier: 'ACME GmbH',
    category: 'FINANCE',
    sollkonto: '1200',
    habenkonto: '1600',
    steuerschluessel: '21',
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'map-2',
    mandantId: null,
    mandantName: null,
    supplier: null,
    category: null,
    sollkonto: '1000',
    habenkonto: '1200',
    steuerschluessel: null,
    createdAt: '2026-01-02T00:00:00Z',
  },
];

const categories = ['CONTRACTS', 'FINANCE', 'GOVERNMENT'];

const contactsPage = {
  items: [
    {
      id: 'contact-1',
      display_name: 'Mandant A GmbH',
      kind: 'ORGANISATION',
      email: null,
      phone: null,
      city: null,
      updated_at: '2026-01-01T00:00:00Z',
    },
  ],
  page: 0,
  size: 1,
  totalItems: 1,
  totalPages: 1,
};

const importReport: KontoMappingImportReport = {
  created: 2,
  updated: 1,
  skipped: [{ line: 4, reason: 'Unbekannter Mandant „Niemand GmbH“.' }],
};

interface Call {
  readonly url: string;
  readonly method: string;
  readonly body: unknown;
}

const calls: Call[] = [];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A backend that serves the mapping and answers every mutation the way the contract does. */
function serve(
  options: { readonly items?: readonly KontoMapping[]; readonly createStatus?: number } = {},
): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = (init?.method ?? 'GET').toUpperCase();
      const body: unknown = init?.body;
      calls.push({ url, method, body });

      if (url.includes('/auth/csrf')) return json({ token: 'test' });
      if (url.includes('/contacts')) return json(contactsPage);
      if (url.endsWith('/settings/konto-mapping/import') && method === 'POST') {
        return json(importReport);
      }
      if (method === 'GET' && url.includes('/settings/konto-mapping')) {
        const items = options.items ?? mappings;
        return json({ items, count: items.length, categories });
      }
      if (method === 'POST') {
        return json({ ...mappings[0], id: 'created' }, options.createStatus ?? 201);
      }
      if (method === 'PATCH') return json({ ...mappings[0], sollkonto: '1300' });
      if (method === 'DELETE') return new Response(null, { status: 204 });
      return json({});
    }),
  );
}

const user = (permissions: readonly string[]): SessionUser => ({
  id: 'u1',
  name: 'Nina Test',
  email: 'nina@example.de',
  roles: [],
  workspaceId: 'w1',
  workspaceName: 'Testkanzlei',
  workspaceType: 'company',
  permissions,
});

const EDITOR = user(['settings.view', 'settings.manage_rules']);
const VIEWER = user(['settings.view']);

function withSession(userValue: SessionUser, ui: ReactNode): ReactElement {
  return <SessionProvider user={userValue}>{ui}</SessionProvider>;
}

function rowFor(supplier: string): HTMLElement {
  const row = screen.getAllByRole('row').find((item) => within(item).queryByText(supplier));
  if (!row) throw new Error(`no row for ${supplier}`);
  return row;
}

describe('KontoMappingPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('renders the mappings and says that without a row the DATEV export stays empty', async () => {
    serve();
    const { container } = renderWithProviders(<KontoMappingPage />);

    expect(await screen.findByText('Mandant A GmbH')).toBeInTheDocument();
    expect(screen.getByText('ACME GmbH')).toBeInTheDocument();
    expect(screen.getByText('FINANCE')).toBeInTheDocument();
    const acme = rowFor('ACME GmbH');
    expect(within(acme).getByText('1200')).toBeInTheDocument();
    expect(within(acme).getByText('1600')).toBeInTheDocument();
    expect(within(acme).getByText('21')).toBeInTheDocument();
    // The whole reason this screen exists is stated on it, not only in the manual.
    expect(screen.getByText('Ohne Zeile hier bleibt der Export leer')).toBeInTheDocument();
    expect(screen.getByText(/Kein Konto-Mapping/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Zum DATEV-Export' })).toHaveAttribute(
      'href',
      '/exports',
    );
    expect(screen.getByText('2 von 2 Zuordnungen')).toBeInTheDocument();

    await expectNoA11yViolations(container);
  });

  it('explains in the empty state what happens without a mapping, and offers the two ways out', async () => {
    serve({ items: [] });
    renderWithProviders(withSession(EDITOR, <KontoMappingPage />));

    expect(await screen.findByText('Noch keine Konten-Zuordnung')).toBeInTheDocument();
    expect(screen.getByText(/überspringt der DATEV-Export jeden Beleg/)).toBeInTheDocument();
    expect(screen.getByText(/die Datei bleibt leer/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Erste Zuordnung anlegen' })).toBeInTheDocument();
    // The header offers the import as well; the empty state repeats it next to the consequence.
    expect(screen.getAllByRole('button', { name: 'CSV importieren' })).toHaveLength(2);
  });

  it('imports a CSV, uploads the chosen file and reports what it skipped', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <KontoMappingPage />));
    await screen.findByText('Mandant A GmbH');

    await userEvent.click(screen.getByRole('button', { name: 'CSV importieren' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/mandant;lieferant;kategorie/)).toBeInTheDocument();

    const file = new File(
      ['mandant;lieferant;kategorie;sollkonto;habenkonto;steuerschluessel\n;;;1000;1200;'],
      'konten.csv',
      { type: 'text/csv' },
    );
    await userEvent.upload(within(dialog).getByLabelText('CSV-Datei'), file);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Importieren' }));

    expect(await within(dialog).findByText('Ergebnis des Imports')).toBeInTheDocument();
    expect(within(dialog).getByText('Angelegt')).toBeInTheDocument();
    expect(within(dialog).getByText('Geändert')).toBeInTheDocument();
    expect(within(dialog).getByText('Nicht übernommen')).toBeInTheDocument();
    expect(within(dialog).getByText(/Zeile 4/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Unbekannter Mandant/)).toBeInTheDocument();

    const upload = calls.find((call) => call.method === 'POST' && call.url.includes('/import'));
    expect(upload?.url).toBe('/api/v1/settings/konto-mapping/import');
    expect(upload?.body).toBeInstanceOf(FormData);
    expect((upload?.body as FormData).get('file')).toBeInstanceOf(File);
  });

  it('creates a mapping with the whole row through the dialog', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <KontoMappingPage />));
    await screen.findByText('Mandant A GmbH');

    await userEvent.click(screen.getByRole('button', { name: 'Zuordnung hinzufügen' }));
    const dialog = await screen.findByRole('dialog');

    await userEvent.selectOptions(
      within(dialog).getByLabelText('Mandant (optional)'),
      'contact-1',
    );
    await userEvent.type(within(dialog).getByLabelText('Lieferant (optional)'), '  ACME GmbH  ');
    await userEvent.selectOptions(within(dialog).getByLabelText('Kategorie (optional)'), 'FINANCE');
    await userEvent.type(within(dialog).getByLabelText('Sollkonto'), '1200');
    await userEvent.type(within(dialog).getByLabelText('Habenkonto'), '1600');
    await userEvent.type(within(dialog).getByLabelText('Steuerschlüssel (optional)'), '21');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Speichern' }));

    await waitFor(() =>
      expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/konto-mapping'))).toBe(
        true,
      ),
    );
    const post = calls.find((call) => call.method === 'POST' && call.url.endsWith('/konto-mapping'));
    expect(JSON.parse(String(post?.body))).toEqual({
      mandantId: 'contact-1',
      supplier: 'ACME GmbH',
      category: 'FINANCE',
      sollkonto: '1200',
      habenkonto: '1600',
      steuerschluessel: '21',
    });
    expect(await screen.findByText('Zuordnung angelegt')).toBeInTheDocument();
  });

  it('refuses an empty account before calling the API', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <KontoMappingPage />));
    await screen.findByText('Mandant A GmbH');

    await userEvent.click(screen.getByRole('button', { name: 'Zuordnung hinzufügen' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Habenkonto'), '1600');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Speichern' }));

    expect(await within(dialog).findByText('Bitte ein Sollkonto angeben.')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
  });

  it('edits and deletes a row, and the delete waits for the confirmation', async () => {
    serve();
    renderWithProviders(withSession(EDITOR, <KontoMappingPage />));
    await screen.findByText('Mandant A GmbH');

    await userEvent.click(within(rowFor('ACME GmbH')).getByRole('button', { name: 'Bearbeiten' }));
    const edit = await screen.findByRole('dialog');
    const sollkonto = within(edit).getByLabelText('Sollkonto');
    await userEvent.clear(sollkonto);
    await userEvent.type(sollkonto, '1300');
    await userEvent.click(within(edit).getByRole('button', { name: 'Speichern' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'PATCH')).toBe(true));
    const patch = calls.find((call) => call.method === 'PATCH');
    expect(patch?.url).toContain('/api/v1/settings/konto-mapping/map-1');
    expect(JSON.parse(String(patch?.body))).toMatchObject({
      mandantId: 'contact-1',
      supplier: 'ACME GmbH',
      sollkonto: '1300',
      habenkonto: '1600',
    });
    expect(await screen.findByText('Zuordnung gespeichert')).toBeInTheDocument();

    await userEvent.click(within(rowFor('ACME GmbH')).getByRole('button', { name: 'Löschen' }));
    const confirm = await screen.findByRole('dialog');
    expect(within(confirm).getByText('Zuordnung löschen?')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false);
    await userEvent.click(within(confirm).getByRole('button', { name: 'Löschen' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'DELETE')).toBe(true));
    expect(calls.find((call) => call.method === 'DELETE')?.url).toContain(
      '/api/v1/settings/konto-mapping/map-1',
    );
    expect(await screen.findByText('Zuordnung gelöscht')).toBeInTheDocument();
  });

  it('filters the rows by what a person types, and says how many are shown', async () => {
    serve();
    renderWithProviders(<KontoMappingPage />);
    await screen.findByText('Mandant A GmbH');

    await userEvent.type(
      screen.getByLabelText('Nach Mandant, Lieferant, Kategorie oder Konto suchen'),
      'acme',
    );
    expect(screen.getByText('1 von 2 Zuordnungen')).toBeInTheDocument();
    expect(screen.getByText('ACME GmbH')).toBeInTheDocument();
    expect(screen.queryByText('Alle Lieferanten')).toBeNull();

    await userEvent.clear(
      screen.getByLabelText('Nach Mandant, Lieferant, Kategorie oder Konto suchen'),
    );
    await userEvent.type(
      screen.getByLabelText('Nach Mandant, Lieferant, Kategorie oder Konto suchen'),
      'zzz',
    );
    expect(screen.getByText('Keine Zuordnung passt zu Ihrer Suche.')).toBeInTheDocument();
  });

  it('shows the list read-only to a person who may see settings but not change them', async () => {
    serve();
    renderWithProviders(withSession(VIEWER, <KontoMappingPage />));
    await screen.findByText('Mandant A GmbH');

    expect(screen.getByText(/Sie dürfen die Zuordnung ansehen, aber nicht ändern/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Zuordnung hinzufügen' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'CSV importieren' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Bearbeiten' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Löschen' })).toBeNull();
  });

  it('shows no mapping at all to a person without settings.view, and does not call the API', async () => {
    serve();
    renderWithProviders(withSession(user(['tasks.view']), <KontoMappingPage />));

    expect(screen.getByText('Kein Zugriff')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Zurück zu den Einstellungen' })).toHaveAttribute(
      'href',
      '/settings',
    );
    expect(calls.filter((call) => call.url.includes('konto-mapping'))).toHaveLength(0);
  });

  it('shows the error state with a retry when the mappings cannot be loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json({ code: 'INTERNAL_ERROR', message: 'boom' }, 500)),
    );
    renderWithProviders(<KontoMappingPage />);

    expect(await screen.findByText('Im Backend ist ein Fehler aufgetreten.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument();
  });
});
