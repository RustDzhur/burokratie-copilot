import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, expectNoA11yViolations } from '@/test/render';
import ImpressumPage from '../impressum/page';
import PrivacyPage from '../datenschutz/page';
import CookiesPage from '../cookies/page';
import TermsPage from '../agb/page';
import WithdrawalPage from '../widerruf/page';
import ProcessingAgreementPage from '../avv/page';
import AccessibilityStatementPage from '../barrierefreiheit/page';

vi.mock('next/navigation', () => ({
  usePathname: () => '/impressum',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const complete = {
  operator: {
    name: 'Muster GmbH', legalForm: 'GmbH', address: 'Musterstraße 1\n10115 Berlin', representative: 'Erika Mustermann',
    email: 'info@example.de', phone: '+49 30 123456', register: 'Amtsgericht Berlin-Charlottenburg', registerNumber: 'HRB 12345',
    vatId: 'DE123456789', hosting: 'Rechenzentrum Frankfurt', disputeResolution: 'NOT_WILLING',
  },
  missing: [],
  version: '2026-10-15',
  trialDays: 60,
  retentionDays: 100,
};

function serve(body: object): void {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })));
}

describe('legal pages', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('the imprint shows the operator\'s own entries and the statements the law asks for', async () => {
    serve(complete);
    const { container } = renderWithProviders(<ImpressumPage />);
    expect(await screen.findByText(/Angaben gemäß § 5 DDG/)).toBeInTheDocument();
    expect(screen.getByText(/Muster GmbH/)).toBeInTheDocument();
    expect(screen.getByText(/Erika Mustermann/)).toBeInTheDocument();
    expect(screen.getByText(/HRB 12345/)).toBeInTheDocument();
    expect(screen.getByText(/DE123456789/)).toBeInTheDocument();
    expect(screen.getByText(/nicht bereit und nicht verpflichtet/)).toBeInTheDocument();
    expect(screen.queryByText(/noch nicht vollständig hinterlegt/)).not.toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('says plainly what is not entered yet instead of inventing it', async () => {
    serve({ ...complete, operator: { email: 'info@example.de' }, missing: ['name', 'address', 'phone', 'disputeResolution'] });
    renderWithProviders(<ImpressumPage />);
    expect(await screen.findByText(/noch nicht vollständig hinterlegt/)).toHaveTextContent('name, address, phone, disputeResolution');
    expect(document.querySelectorAll('mark[data-missing]').length).toBeGreaterThan(0);
  });

  it('every legal page renders, carries the version and names the operator where the law wants it', async () => {
    for (const [Page, expected] of [
      [PrivacyPage, /Verantwortlicher im Sinne der DSGVO ist: Muster GmbH/],
      [TermsPage, /^Allgemeine Geschäftsbedingungen$/],
      [WithdrawalPage, /Muster-Widerrufsformular$/],
      [ProcessingAgreementPage, /Vertrag zur Auftragsverarbeitung \(AVV\)/],
      [CookiesPage, /keine Tracking-, Analyse- oder Werbe-Cookies/],
      [AccessibilityStatementPage, /teilweise/],
    ] as const) {
      serve(complete);
      const { unmount } = renderWithProviders(<Page />);
      expect(await screen.findByText(expected)).toBeInTheDocument();
      expect(screen.getByText(/Stand: 2026-10-15/)).toBeInTheDocument();
      unmount();
    }
  });

  it('the privacy notice states the retention from the platform\'s own settings', async () => {
    serve({ ...complete, retentionDays: 77, trialDays: 14 });
    renderWithProviders(<PrivacyPage />);
    expect(await screen.findByText(/77 Tage erhalten/)).toBeInTheDocument();
    expect(screen.getByText(/kostenlosen Test von 14 Tagen/)).toBeInTheDocument();
  });

  it('the cookie page lists every entry the application writes', async () => {
    serve(complete);
    renderWithProviders(<CookiesPage />);
    for (const name of ['bc_session', 'XSRF-TOKEN', 'bc_locale', 'bc_appearance', 'bc.appearance', 'bc.iris.conversation']) {
      expect(await screen.findByText(name)).toBeInTheDocument();
    }
  });
});
