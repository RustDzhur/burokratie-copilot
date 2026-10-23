import { describe, expect, it } from 'vitest';
import { renderWithProviders, screen, within } from '@/test/render';
import { formatCurrency } from '@/i18n/format';
import { BelegdatenCard } from '../belegdaten-card';
import { deadlineTone } from '../deadline-badge';

/**
 * The booking-data block of a voucher (Kanzlei edition, §5.1).
 *
 * <p>What is checked is the honesty of the block: the figures the extraction found are shown with the
 * German formatting, a deadline under three days is red, a passed one says "überfällig", and a value
 * the pipeline did not find is a dash instead of a plausible-looking default.</p>
 */
describe('Belegdaten on the voucher card', () => {
  const sectionOf = (): HTMLElement =>
    screen.getByRole('heading', { name: 'Belegdaten' }).closest('section') as HTMLElement;

  it('shows the extracted amount, voucher date, supplier and the next deadline', () => {
    renderWithProviders(
      <BelegdatenCard
        amount={1234.56}
        amountCurrency="EUR"
        documentDate="2026-03-15"
        supplierName="Muster GmbH"
        deadlineDate="2026-04-01"
        deadlineDaysLeft={2}
      />,
    );

    const section = sectionOf();
    // The exact rendering (including the non-breaking space before €) is the locale's, not the test's:
    // comparing textContent keeps the assertion about the formatting rather than about whitespace.
    const definitions = within(section).getAllByRole('definition');
    expect(definitions[0]?.textContent).toBe(formatCurrency(1234.56, 'de', 'EUR'));
    expect(within(section).getByText('15.03.2026')).toBeInTheDocument();
    expect(within(section).getByText('Muster GmbH')).toBeInTheDocument();
    expect(within(section).getByText('01.04.2026')).toBeInTheDocument();
    // Under three days the badge is red, and the word itself says how much time is left.
    const badge = within(section).getByText('2 Tage');
    expect(badge.closest('[data-tone="danger"]')).not.toBeNull();
  });

  it('says "überfällig" in red once the deadline has passed', () => {
    renderWithProviders(
      <BelegdatenCard deadlineDate="2026-03-01" deadlineDaysLeft={-4} />,
    );

    const badge = within(sectionOf()).getByText('überfällig');
    expect(badge.closest('[data-tone="danger"]')).not.toBeNull();
  });

  it('shows a dash for every value the pipeline did not extract', () => {
    renderWithProviders(<BelegdatenCard />);

    const section = sectionOf();
    // Betrag, Belegdatum, Lieferant and Frist: four rows, four dashes. Nothing is invented in their place.
    expect(within(section).getAllByText('—')).toHaveLength(4);
    expect(within(section).queryByText(/\d+ Tage|überfällig/)).toBeNull();
  });

  it('colours the badge by the days left: red under three, yellow up to two weeks, neutral beyond', () => {
    expect(deadlineTone(-1)).toBe('danger');
    expect(deadlineTone(0)).toBe('danger');
    expect(deadlineTone(2)).toBe('danger');
    expect(deadlineTone(3)).toBe('warning');
    expect(deadlineTone(14)).toBe('warning');
    expect(deadlineTone(15)).toBe('neutral');
  });
});
