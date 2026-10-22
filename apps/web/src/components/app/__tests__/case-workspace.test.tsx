import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, userEvent } from '@/test/render';
import { CaseWorkspace } from '../case-workspace';

vi.mock('next/navigation', () => ({
  usePathname: () => '/cases/c1',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 'c1' }),
  useSearchParams: () => new URLSearchParams(),
}));

const workspace = {
  goal: 'Erhöhung klären',
  next_action: 'Widerspruch senden',
  criteria: [
    { id: 'k1', kind: 'REPLY_RECEIVED', description: 'Antwort des Anbieters liegt vor', status: 'OPEN' },
    { id: 'k2', kind: 'TASK_DONE', description: 'Widerspruch ist versendet', status: 'MET' },
  ],
};

type Call = { url: string; method: string; body: string | null };
const calls: Call[] = [];

function serve(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : null });
      const body = url.endsWith('/workspace') ? workspace : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('CaseWorkspace', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows the goal and which criteria are met and open, without asking for the removed plan', async () => {
    serve();
    const { container } = renderWithProviders(<CaseWorkspace caseId="c1" />);
    expect(await screen.findByDisplayValue('Erhöhung klären')).toBeInTheDocument();
    const criteria = screen.getAllByTestId('criterion');
    expect(criteria).toHaveLength(2);
    expect(criteria[0]).toHaveTextContent('Offen');
    expect(criteria[1]).toHaveTextContent('Erfüllt');
    expect(calls.some((call) => call.url.endsWith('/workspace'))).toBe(true);
    expect(calls.some((call) => call.url.endsWith('/plan'))).toBe(false);
    await expectNoA11yViolations(container);
  });

  it('marking a criterion met needs the evidence', async () => {
    serve();
    renderWithProviders(<CaseWorkspace caseId="c1" />);
    await screen.findByDisplayValue('Erhöhung klären');

    await userEvent.click(screen.getByRole('button', { name: 'Als erfüllt markieren' }));
    expect(await screen.findByText('Ein Nachweis oder eine Begründung ist nötig.')).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0);
  });
});
