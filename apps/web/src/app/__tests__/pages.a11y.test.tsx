import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen, waitFor } from '@/test/render';
import DashboardPage from '../(app)/dashboard/page';
import InboxPage from '../(app)/inbox/page';
import AccessibilityPage from '../(app)/accessibility/page';
import LoginPage from '../(auth)/login/page';
import LandingPage from '../(public)/page';
import DesignSystemPage from '../design-system/page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({ id: 'doc-1' }),
  useSearchParams: () => new URLSearchParams(),
}));

function stubFetchFailure(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }),
  );
}

function stubFetchSuccess(payload: unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(JSON.stringify(payload), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    ),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

/**
 * Accessibility checks for the key screens.
 *
 * Both the failure and the success path are checked, because an error state that
 * is announced badly is just as broken as a missing one (§29, §23).
 */
describe('key screens — accessibility', () => {
  it('landing page renders without violations', async () => {
    stubFetchFailure();
    const { container } = renderWithProviders(<LandingPage />);
    await expectNoA11yViolations(container);
  });

  it('login page renders without violations and labels every field', async () => {
    stubFetchFailure();
    const { container } = renderWithProviders(<LoginPage />);

    expect(screen.getByLabelText('E-Mail-Adresse')).toBeInTheDocument();
    expect(screen.getByLabelText('Passwort')).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('dashboard shows an announced error state when the backend is unreachable', async () => {
    stubFetchFailure();
    const { container } = renderWithProviders(<DashboardPage />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/nicht erreichbar/i);
    await expectNoA11yViolations(container);
  });

  it('dashboard renders attention cards without violations once data arrives', async () => {
    stubFetchSuccess({
      generatedAt: new Date().toISOString(),
      counts: { pendingDocuments: 4, todayTasks: 2, overdueTasks: 1 },
      activity: [
        { id: 'a1', at: new Date().toISOString(), actor: 'Anna', summary: 'Dokument erfasst' },
      ],
      automationStatus: [{ status: 'active', count: 3 }],
      integrationStatus: [{ status: 'connected', count: 2 }],
    });

    const { container } = renderWithProviders(<DashboardPage />);

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Dokumente in Verarbeitung')).toBeInTheDocument());
    await expectNoA11yViolations(container);
  });

  it('inbox renders its error state without violations', async () => {
    stubFetchFailure();
    const { container } = renderWithProviders(<InboxPage />);

    await screen.findByRole('alert');
    await expectNoA11yViolations(container);
  });

  it('accessibility settings expose labelled controls without violations', async () => {
    stubFetchFailure();
    const { container } = renderWithProviders(<AccessibilityPage />);

    expect(screen.getByRole('switch', { name: /Hoher Kontrast/ })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Farbschema' })).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('design-system showcase has no violations in its default state', async () => {
    stubFetchFailure();
    const { container } = renderWithProviders(<DesignSystemPage />);
    await waitFor(() => expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1));
    await expectNoA11yViolations(container);
  });
});
