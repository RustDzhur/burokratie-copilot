import { beforeEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { renderWithProviders, screen, waitFor } from '@/test/render';
import { api } from '@/lib/api';
import { TypeChooser, type TypeChoice } from '../type-chooser';

vi.mock('@/lib/api', () => ({ api: { documents: { pipeline: vi.fn(), catalogue: vi.fn() } } }));
const documents = api.documents as unknown as Record<'pipeline' | 'catalogue', ReturnType<typeof vi.fn>>;

const catalogue = [
  { code: 'rechnung', category: 'FINANCE', names: { de: 'Rechnung' } },
  { code: 'businessplan', category: 'BUSINESS', names: { de: 'Businessplan' } },
];
const flow = (extra: object) => ({ documentTypeName: null, typeNames: null, suggestedType: null, ...extra });

function setup(initial: string | null = null) {
  const choices: TypeChoice[] = [];
  const view = renderWithProviders(<TypeChooser documentId="d1" value={initial} onChange={(c) => choices.push(c)} />);
  return { choices, view };
}

describe('TypeChooser', () => {
  beforeEach(() => {
    documents.catalogue.mockResolvedValue(catalogue);
  });

  it('is not ready while there is no type; offers the suggestion by name and the whole list; reports what was chosen', async () => {
    documents.pipeline.mockResolvedValue(flow({ suggestedType: { code: 'rechnung', name: 'Rechnung', confidence: 0.7, rationale: null } }));
    const user = userEvent.setup();
    const { choices } = setup();

    expect(await screen.findByText(/noch keinen Typ/)).toBeInTheDocument();
    await waitFor(() => expect(choices.at(-1)).toEqual({ code: null, ready: false }));
    await user.click(screen.getByRole('button', { name: '„Rechnung“ übernehmen' }));
    expect(choices.at(-1)).toEqual({ code: 'rechnung', ready: true });

    await user.selectOptions(screen.getByLabelText('Typ wählen'), 'businessplan');
    expect(choices.at(-1)).toEqual({ code: 'businessplan', ready: true });
    expect(screen.getByRole('option', { name: 'Unternehmen · Businessplan' })).toBeInTheDocument();
  });

  it('is ready at once when the document already has a type, and lets it be changed', async () => {
    documents.pipeline.mockResolvedValue(flow({ documentTypeName: 'Rechnung', typeNames: { de: 'Rechnung' } }));
    const { choices } = setup();
    expect(await screen.findByText('Erkannt als: Rechnung.')).toBeInTheDocument();
    await waitFor(() => expect(choices.at(-1)).toEqual({ code: null, ready: true }));
    expect(screen.getByLabelText('Typ ändern')).toBeInTheDocument();
  });
});
