import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations, renderWithProviders, screen } from '@/test/render';
import { positionOf, RelationshipGraph, type GraphNode } from '../relationship-graph';

const nodes: GraphNode[] = [
  { id: 'd1', kind: 'document', label: 'Rechnung.pdf', href: '/documents/d1', suggested: true },
  { id: 'c1', kind: 'contract', label: 'Stromvertrag', href: '/contracts/c1' },
  { id: 'i1', kind: 'invoice', label: 'R-77', href: '/invoices/i1' },
];

describe('RelationshipGraph', () => {
  it('draws every connection and lists the same items with links, marking an unconfirmed one in words', async () => {
    const { container } = renderWithProviders(<RelationshipGraph centre="Stadtwerke" nodes={nodes} />);

    expect(screen.getByRole('img', { name: 'Verbindungen dieses Kontakts: 3 Einträge' })).toBeInTheDocument();
    expect(container.querySelectorAll('svg circle')).toHaveLength(1 + nodes.length);
    expect(screen.getAllByTestId('graph-item')).toHaveLength(3);
    expect(screen.getByRole('link', { name: 'Stromvertrag' })).toHaveAttribute('href', '/contracts/c1');
    expect(screen.getByText('Vorschlag', { exact: false })).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('says so when nothing is connected, and caps a very large neighbourhood while naming how many are left out', () => {
    const { rerender } = renderWithProviders(<RelationshipGraph centre="X" nodes={[]} />);
    expect(screen.getByText('Noch ist nichts verbunden.')).toBeInTheDocument();

    const many: GraphNode[] = Array.from({ length: 30 }, (_, i) => ({ id: `n${i}`, kind: 'document', label: `Doc ${i}`, href: `/documents/n${i}` }));
    rerender(<RelationshipGraph centre="X" nodes={many} />);
    expect(screen.getAllByTestId('graph-item')).toHaveLength(24);
    expect(screen.getByText('und 6 weitere')).toBeInTheDocument();
  });

  it('places nodes evenly on a ring, the first at the top', () => {
    expect(positionOf(0, 4)).toEqual({ x: 180, y: 50 });
    expect(positionOf(2, 4)).toEqual({ x: 180, y: 310 });
  });
});
