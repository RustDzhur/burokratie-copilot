import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, within } from '@/test/render';
import { DataTable, Pagination, Badge, StatusPill, Avatar, Breadcrumbs } from '../data';
import { Tabs } from '../tabs';
import type { Column } from '../data';

interface Row {
  readonly id: string;
  readonly name: string;
  readonly amount: number;
}

const ROWS: readonly Row[] = [
  { id: '1', name: 'Erste Position', amount: 10 },
  { id: '2', name: 'Zweite Position', amount: 20 },
];

const COLUMNS: readonly Column<Row>[] = [
  { key: 'name', header: 'Bezeichnung', render: (row) => row.name, sortable: true },
  { key: 'amount', header: 'Betrag', render: (row) => row.amount, align: 'end' },
];

describe('DataTable', () => {
  it('renders a caption and a header row', () => {
    renderWithProviders(
      <DataTable caption="Positionen" columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} />,
    );

    expect(screen.getByRole('table', { name: 'Positionen' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: /Bezeichnung/ })).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  it('reports sort changes and exposes aria-sort', async () => {
    const onSortChange = vi.fn();
    renderWithProviders(
      <DataTable
        caption="Positionen"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={(row) => row.id}
        sort={{ key: 'name', direction: 'asc' }}
        onSortChange={onSortChange}
      />,
    );

    const header = screen.getByRole('columnheader', { name: /Bezeichnung/ });
    expect(header).toHaveAttribute('aria-sort', 'ascending');

    await userEvent.click(screen.getByRole('button', { name: /Bezeichnung/ }));
    expect(onSortChange).toHaveBeenCalledWith({ key: 'name', direction: 'desc' });
  });

  it('is operable with the keyboard only', async () => {
    const onSortChange = vi.fn();
    renderWithProviders(
      <DataTable
        caption="Positionen"
        columns={COLUMNS}
        rows={ROWS}
        rowKey={(row) => row.id}
        onSortChange={onSortChange}
      />,
    );

    await userEvent.tab();
    expect(screen.getByRole('button', { name: /Bezeichnung/ })).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(onSortChange).toHaveBeenCalledTimes(1);
  });

  it('renders the empty state inside the table instead of a blank area', () => {
    renderWithProviders(
      <DataTable
        caption="Positionen"
        columns={COLUMNS}
        rows={[]}
        rowKey={(row) => row.id}
        emptyState={<p>Keine Daten vorhanden</p>}
      />,
    );

    expect(screen.getByText('Keine Daten vorhanden')).toBeInTheDocument();
  });

  it('renders skeleton rows while loading and hides the empty state', () => {
    renderWithProviders(
      <DataTable
        caption="Positionen"
        columns={COLUMNS}
        rows={[]}
        rowKey={(row) => row.id}
        loading
        emptyState={<p>Keine Daten vorhanden</p>}
      />,
    );

    expect(screen.queryByText('Keine Daten vorhanden')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('renders links for records and row actions', () => {
    renderWithProviders(
      <DataTable
        caption="Positionen"
        columns={[{ ...COLUMNS[0]!, link: (row) => `/documents/${row.id}` }]}
        rows={ROWS}
        rowKey={(row) => row.id}
        rowActions={(row) => <button type="button">Aktion {row.id}</button>}
      />,
    );

    expect(screen.getByRole('link', { name: 'Erste Position' })).toHaveAttribute(
      'href',
      '/documents/1',
    );
    expect(screen.getByRole('button', { name: 'Aktion 2' })).toBeInTheDocument();
  });
});

describe('Pagination', () => {
  it('marks the current page and navigates with buttons', async () => {
    const onPageChange = vi.fn();
    renderWithProviders(
      <Pagination page={2} totalPages={5} totalItems={100} onPageChange={onPageChange} />,
    );

    expect(screen.getByRole('button', { name: 'Seite 3' })).toHaveAttribute('aria-current', 'page');

    await userEvent.click(screen.getByRole('button', { name: 'Weiter' }));
    expect(onPageChange).toHaveBeenCalledWith(3);

    await userEvent.click(screen.getByRole('button', { name: 'Zurück' }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('disables navigation at the boundaries', () => {
    renderWithProviders(<Pagination page={0} totalPages={1} onPageChange={() => undefined} />);
    expect(screen.getByRole('button', { name: 'Zurück' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Weiter' })).toBeDisabled();
  });
});

describe('Tabs', () => {
  const tabs = [
    { id: 'one', label: 'Erste', content: <p>Inhalt eins</p> },
    { id: 'two', label: 'Zweite', content: <p>Inhalt zwei</p> },
    { id: 'three', label: 'Dritte', content: <p>Inhalt drei</p> },
  ];

  it('renders the first tab selected with its panel visible', () => {
    renderWithProviders(<Tabs label="Bereiche" tabs={tabs} />);

    expect(screen.getByRole('tab', { name: 'Erste' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Inhalt eins');
  });

  it('moves between tabs with the arrow keys and selects on click', async () => {
    renderWithProviders(<Tabs label="Bereiche" tabs={tabs} />);

    await userEvent.tab();
    expect(screen.getByRole('tab', { name: 'Erste' })).toHaveFocus();

    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Zweite' })).toHaveFocus();

    await userEvent.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(screen.getByRole('tab', { name: 'Dritte' })).toHaveFocus();

    await userEvent.click(screen.getByRole('tab', { name: 'Dritte' }));
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Inhalt drei');
  });
});

describe('Badge, StatusPill, Avatar and Breadcrumbs', () => {
  it('renders tone information without relying on colour only', () => {
    renderWithProviders(<StatusPill label="Prüfung erforderlich" status="needs_review" />);
    expect(screen.getByText('Prüfung erforderlich')).toBeInTheDocument();
    expect(screen.getByText('Prüfung erforderlich').closest('[data-tone]')).toHaveAttribute(
      'data-tone',
      'warning',
    );
  });

  it('renders badges with text content', () => {
    renderWithProviders(<Badge tone="success">Freigegeben</Badge>);
    expect(screen.getByText('Freigegeben')).toBeInTheDocument();
  });

  it('exposes the avatar name to assistive technology', () => {
    renderWithProviders(<Avatar name="Anna Beispiel" status="online" />);
    expect(screen.getByRole('img', { name: 'Anna Beispiel' })).toBeInTheDocument();
  });

  it('renders breadcrumbs with a current page marker', () => {
    renderWithProviders(
      <Breadcrumbs
        items={[
          { label: 'Dashboard', href: '/dashboard' },
          { label: 'Dokumente', href: '/documents' },
          { label: 'Aktuelles Dokument' },
        ]}
      />,
    );

    const nav = screen.getByRole('navigation', { name: 'Sie befinden sich hier' });
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute(
      'href',
      '/dashboard',
    );
    expect(within(nav).getByText('Aktuelles Dokument')).toHaveAttribute('aria-current', 'page');
  });
});
