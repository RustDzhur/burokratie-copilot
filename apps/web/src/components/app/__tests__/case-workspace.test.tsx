import { afterEach, describe, expect, it, vi } from 'vitest';
import { within } from '@testing-library/react';
import { expectNoA11yViolations, renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
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
const plan = {
  run: { id: 'r1', status: 'RUNNING' },
  steps: [
    { step_key: 'entwurf', status: 'PENDING', ready: true, blockedBy: [], input: { action: 'DRAFT_LETTER', title: 'Widerspruch entwerfen', level: 2, auto: false, dependsOn: [], evidenceRequired: true } },
    { step_key: 'freigabe', status: 'PENDING', ready: false, blockedBy: ['entwurf'], input: { action: 'REQUEST_APPROVAL', title: 'Freigabe anfordern', level: 3, auto: false, dependsOn: ['entwurf'] } },
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
      const body = url.endsWith('/workspace') ? workspace : url.endsWith('/plan') ? plan : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

describe('CaseWorkspace', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('shows the goal, which criteria are met and open, and the plan with what blocks each step', async () => {
    serve();
    const { container } = renderWithProviders(<CaseWorkspace caseId="c1" />);
    expect(await screen.findByDisplayValue('Erhöhung klären')).toBeInTheDocument();
    const criteria = screen.getAllByTestId('criterion');
    expect(criteria).toHaveLength(2);
    expect(criteria[0]).toHaveTextContent('Offen');
    expect(criteria[1]).toHaveTextContent('Erfüllt');
    const steps = screen.getAllByTestId('step');
    expect(steps[0]).toHaveTextContent('Widerspruch entwerfen');
    expect(steps[1]).toHaveTextContent('Wartet auf: entwurf');
    expect(within(steps[1]!).queryByRole('button', { name: 'Starten' })).not.toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('only a step that is ready can be started, and marking a criterion met needs the evidence', async () => {
    serve();
    renderWithProviders(<CaseWorkspace caseId="c1" />);
    await screen.findByDisplayValue('Erhöhung klären');

    await userEvent.click(screen.getByRole('button', { name: 'Als erfüllt markieren' }));
    expect(await screen.findByText('Ein Nachweis oder eine Begründung ist nötig.')).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0);

    await userEvent.click(screen.getAllByRole('button', { name: 'Starten' })[0]!);
    await waitFor(() => expect(calls.some((call) => call.url.includes('/plan/steps/entwurf/start'))).toBe(true));
  });
});
