import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen } from '@/test/render';
import { Topbar } from '../topbar';

vi.mock('next/navigation', () => ({
  usePathname: () => '/dashboard',
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

describe('Topbar: documents waiting for a check', () => {
  const props = { onOpenSearch: vi.fn(), onOpenMobileNav: vi.fn(), user: null };

  it('shows a link to the check while documents wait, with their number', () => {
    renderWithProviders(<Topbar {...props} pendingReview={3} />);
    expect(screen.getByRole('link', { name: '3 Dokument(e) zur Prüfung' })).toHaveAttribute('href', '/review');
  });

  it('shows nothing when nothing waits', () => {
    renderWithProviders(<Topbar {...props} pendingReview={0} />);
    expect(screen.queryByTestId('pending-review')).not.toBeInTheDocument();
  });
});
