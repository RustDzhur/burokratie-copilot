import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import RulesPage from '../page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/rules',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const rules = [
  {
    key: 'radar.invoice.amount_change_percent',
    category: 'OTHER',
    description: { de: 'Abweichung des Rechnungsbetrags in Prozent', en: 'Invoice amount deviation in percent', ru: 'Отклонение суммы счёта' },
    type: 'NUMBER',
    min: 0,
    max: 1000,
    allowed: null,
    default: 10,
    value: 25,
    overridden: true,
    version: 2,
  },
  {
    key: 'radar.enabled',
    category: 'OTHER',
    description: { de: 'Radar eingeschaltet', en: 'Radar enabled', ru: 'Радар включён' },
    type: 'BOOLEAN',
    min: null,
    max: null,
    allowed: null,
    default: true,
    value: true,
    overridden: false,
    version: 0,
  },
];

type Call = { url: string; method: string; body: string | null };
const calls: Call[] = [];

function serve(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : null });
      const body = url.endsWith('/history')
        ? [{ version: 1, previous_value: '10', new_value: '25', change_reason: 'Zu viele Hinweise', created_at: '2026-10-15T08:00:00Z' }]
        : init?.method === 'PUT' || init?.method === 'POST'
          ? {}
          : rules;
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('RulesPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows each rule with the value in force, the platform default, the version and whether it was changed', async () => {
    serve();
    const { container } = renderWithProviders(<RulesPage />);

    expect(await screen.findByText('Abweichung des Rechnungsbetrags in Prozent')).toBeInTheDocument();
    expect(screen.getByText('Von Ihnen geändert')).toBeInTheDocument();
    expect(screen.getByText('Standard der Plattform')).toBeInTheDocument();
    expect(screen.getByText(/Standard: 10 · Version: 2 · Erlaubt: 0 bis 1000/)).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('does not save without a reason; with one it sends the value and the reason', async () => {
    const user = userEvent.setup();
    serve();
    renderWithProviders(<RulesPage />);
    await screen.findByText('Abweichung des Rechnungsbetrags in Prozent');

    await user.click(screen.getAllByRole('button', { name: 'Speichern' })[0]!);
    expect(await screen.findByText('Bitte eine Begründung angeben.')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'PUT')).toBe(false);

    await user.type(screen.getAllByLabelText('Begründung der Änderung')[0]!, 'Ruhiger');
    await user.click(screen.getAllByRole('button', { name: 'Speichern' })[0]!);
    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true));
    const put = calls.find((call) => call.method === 'PUT')!;
    expect(put.url).toContain('/settings/rules/radar.invoice.amount_change_percent');
    expect(JSON.parse(put.body ?? '{}')).toEqual({ value: 25, reason: 'Ruhiger' });
  });

  it('offers the way back to the default only for a changed rule, and shows the history with the reason', async () => {
    const user = userEvent.setup();
    serve();
    renderWithProviders(<RulesPage />);
    await screen.findByText('Abweichung des Rechnungsbetrags in Prozent');

    expect(screen.getAllByRole('button', { name: 'Zurück zum Standard' })).toHaveLength(1);
    await user.click(screen.getAllByRole('button', { name: 'Verlauf' })[0]!);
    expect(await screen.findByText(/Zu viele Hinweise/)).toBeInTheDocument();
    expect(screen.getByText(/vorher: 10/)).toBeInTheDocument();
  });
});
