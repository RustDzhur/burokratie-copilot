import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { DocumentTypeChanger } from '../document-type-changer';

const calls: { url: string; body: string | null }[] = [];

describe('DocumentTypeChanger', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('names the current type, and filing under another type tells the server and reloads', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        calls.push({ url, body: typeof init?.body === 'string' ? init.body : null });
        const body = url.includes('/documents/types')
          ? [
              { code: 'rechnung', category: 'FINANCE', names: { de: 'Rechnung', en: 'Invoice', ru: 'Счёт' }, audiences: 'company', requiresReview: false },
              { code: 'vertrag', category: 'CONTRACTS', names: { de: 'Vertrag', en: 'Contract', ru: 'Договор' }, audiences: 'company', requiresReview: true },
            ]
          : {};
        return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }),
    );
    const onChanged = vi.fn();
    renderWithProviders(<DocumentTypeChanger documentId="d1" currentName="Vertrag" onChanged={onChanged} />);

    expect(screen.getByText('Aktueller Typ: Vertrag')).toBeInTheDocument();
    const apply = screen.getByRole('button', { name: 'In dieses Fach legen' });
    expect(apply).toBeDisabled();
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(1));
    await user.selectOptions(screen.getByLabelText('Typ wählen'), 'rechnung');
    await user.click(apply);

    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(calls.some((call) => call.url.includes('/documents/d1/type') && (call.body ?? '').includes('"typeCode":"rechnung"'))).toBe(true);
  });
});
