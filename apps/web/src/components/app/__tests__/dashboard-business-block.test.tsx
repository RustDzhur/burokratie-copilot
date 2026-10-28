import { describe, expect, it } from 'vitest';
import { renderWithProviders, screen } from '@/test/render';
import { DashboardBusinessBlock } from '../dashboard-business-block';

describe('DashboardBusinessBlock', () => {
  it('shows receivables, payables and tasks per person as the server sent them', () => {
    renderWithProviders(
      <DashboardBusinessBlock
        business={{
          receivables: [{ currency: 'EUR', count: 2, open_amount: 250, overdue_count: 1, overdue_amount: 100 }],
          payables: [],
          tasksByPerson: [{ user_id: 'u1', name: 'Erika', open: 3, overdue: 1 }],
        }}
      />,
    );
    expect(screen.getByText(/250,00/)).toBeInTheDocument();
    expect(screen.getByText(/2 Rechnungen offen, davon 1 überfällig/)).toBeInTheDocument();
    expect(screen.getByText('Keine offenen Rechnungen.')).toBeInTheDocument();
    expect(screen.getByText(/3 offen, 1 überfällig/)).toBeInTheDocument();
  });

  it('renders nothing when the server sent no business figures (the reader may not see them)', () => {
    const { container } = renderWithProviders(<DashboardBusinessBlock business={{}} />);
    expect(container).toBeEmptyDOMElement();
  });
});
