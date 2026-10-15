import { beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { renderWithProviders, screen, waitFor } from '@/test/render';
import { api } from '@/lib/api';
import type { StoredExplanation } from '@/lib/api/types';
import { DocumentExplanation } from '../document-explanation';

vi.mock('@/lib/api', () => ({
  api: {
    documents: {
      explanation: vi.fn(),
      explain: vi.fn(),
      translation: vi.fn(),
      translate: vi.fn(),
    },
  },
}));

const documents = api.documents as unknown as Record<'explanation' | 'explain' | 'translation' | 'translate', ReturnType<typeof vi.fn>>;

const explanation: StoredExplanation = {
  id: 'e1',
  createdAt: '2026-10-15T10:00:00Z',
  current: true,
  content: {
    language: 'de',
    disclaimer: 'Von einer KI erstellt. Maßgeblich ist das Original.',
    blocks: [
      {
        key: 'AMOUNTS',
        claims: [
          { text: 'Offen sind 280,00 EUR.', type: 'SOURCE_FACT', quote: 'über 280,00 EUR ist noch offen', page: 2 },
          { text: 'Das ist vermutlich die Hauptforderung.', type: 'AI_INTERPRETATION' },
        ],
      },
      { key: 'CONSEQUENCES', claims: [{ text: 'Diese Aussage hat eine rechtliche Seite.', type: 'REQUIRES_EXPERT_REVIEW' }] },
    ],
  },
};

describe('DocumentExplanation', () => {
  beforeEach(() => {
    Object.values(documents).forEach((fn) => fn.mockReset());
  });

  it('names the type of every claim in words and opens the page of a quoted fact', async () => {
    documents.explanation.mockResolvedValue(explanation);
    renderWithProviders(<DocumentExplanation documentId="doc-1" />);

    expect(await screen.findByText('Offen sind 280,00 EUR.')).toBeInTheDocument();
    expect(screen.getByText('Steht im Dokument')).toBeInTheDocument();
    expect(screen.getByText('Deutung der KI')).toBeInTheDocument();
    expect(screen.getByText('Fachperson nötig')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Seite 2 des Originals öffnen' })).toHaveAttribute(
      'href',
      '/api/v1/documents/doc-1/content#page=2',
    );
    expect(screen.getByRole('heading', { name: 'Beträge' })).toBeInTheDocument();
    expect(screen.getByText(/Maßgeblich ist das Original/)).toBeInTheDocument();
    expect(screen.getByText(/nicht das Dokument/)).toBeInTheDocument();
  });

  it('offers to create an explanation when there is none, and says so when the answer was not accepted', async () => {
    const user = userEvent.setup();
    documents.explanation.mockResolvedValue(null);
    documents.explain.mockResolvedValue({ kind: 'REJECTED', reason: 'Die Antwort hatte nicht die verlangte Form.' });
    renderWithProviders(<DocumentExplanation documentId="doc-1" />);

    expect(await screen.findByText('Noch nichts erstellt.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Erstellen' }));

    expect(await screen.findByTestId('explanation-outcome')).toHaveTextContent('Die Antwort wurde nicht übernommen.');
    expect(documents.explain).toHaveBeenCalledWith('doc-1', 'de');
  });

  it('marks a translation as machine translation and keeps it apart from the original', async () => {
    const user = userEvent.setup();
    documents.explanation.mockResolvedValue(null);
    documents.translation.mockResolvedValue({
      id: 't1',
      createdAt: '2026-10-15T10:00:00Z',
      current: false,
      content: { language: 'en', disclaimer: 'Written by an AI.', text: 'Payment reminder for 280,00 EUR.', machineTranslation: true },
    } satisfies StoredExplanation);
    renderWithProviders(<DocumentExplanation documentId="doc-1" />);

    await user.click(screen.getByRole('button', { name: 'Übersetzung' }));

    expect(await screen.findByText('Payment reminder for 280,00 EUR.')).toBeInTheDocument();
    expect(screen.getByText('Maschinelle Übersetzung')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('neu gelesen');
    await waitFor(() => expect(documents.translation).toHaveBeenCalledWith('doc-1', 'de', false));
  });
});
