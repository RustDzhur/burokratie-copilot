import { describe, expect, it } from 'vitest';
import { renderWithProviders, screen } from '@/test/render';
import type { CaseDeadline } from '@/lib/api/types';
import { DeadlineList } from '../deadline-list';

const read: CaseDeadline = {
  id: 'd1',
  type: 'ASSUMED_LEGAL',
  dueDate: '2026-11-03',
  source: 'DOCUMENT',
  confirmation: 'UNCONFIRMED',
  sourceDocumentId: 'doc-1',
  sourcePage: 2,
  sourceSnippet: 'Frist: 03.11.2026',
  ruleVersion: null,
  note: null,
};

describe('DeadlineList', () => {
  it('shows a date nobody confirmed as unconfirmed, with its page, its text and the way to the document', () => {
    renderWithProviders(<DeadlineList deadlines={[read]} />);
    expect(screen.getByText('Unbestätigt – bitte prüfen')).toBeInTheDocument();
    expect(screen.queryByText('Bestätigt')).not.toBeInTheDocument();
    expect(screen.getByText(/aus dem Dokument gelesen, Seite 2/)).toBeInTheDocument();
    expect(screen.getByText('Frist: 03.11.2026')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Dokument öffnen' })).toHaveAttribute('href', '/documents/doc-1');
    expect(screen.getByText(/keine geprüfte rechtliche Frist/)).toBeInTheDocument();
  });

  it('shows a confirmed date, counted from a rule, with the rule version and without the warning', () => {
    renderWithProviders(
      <DeadlineList
        deadlines={[{ ...read, id: 'd2', type: 'CONFIRMED_LEGAL', source: 'RULE', confirmation: 'CONFIRMED', sourceDocumentId: null, sourcePage: null, sourceSnippet: null, ruleVersion: 3 }]}
      />,
    );
    expect(screen.getByText('Bestätigt')).toBeInTheDocument();
    expect(screen.getByText(/nach Regel berechnet, Regel, Version 3/)).toBeInTheDocument();
    expect(screen.queryByText(/keine geprüfte rechtliche Frist/)).not.toBeInTheDocument();
  });
});
