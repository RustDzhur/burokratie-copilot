import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import type { MandantRoutingResult } from '@/lib/api/types';
import { MandantReroute } from '../mandant-reroute';

interface Call {
  readonly url: string;
  readonly method: string;
}

const calls: Call[] = [];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function serve(result: MandantRoutingResult, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), method: (init?.method ?? 'GET').toUpperCase() });
      return json(result, status);
    }),
  );
}

const ASSIGNED: MandantRoutingResult = {
  documentId: 'doc-1',
  status: 'ASSIGNED',
  mandantId: 'contact-1',
  mandantName: 'ACME GmbH',
  confidence: 0.95,
  signals: [{ type: 'IBAN', value: 'DE89 3704 0044 0532 0130 00', strength: 0.95 }],
  candidates: [{ mandantId: 'contact-1', mandantName: 'ACME GmbH', confidence: 0.95 }],
};

describe('MandantReroute', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('determines the client, names it with the confidence and reloads the card', async () => {
    serve(ASSIGNED);
    const onRouted = vi.fn();
    renderWithProviders(<MandantReroute documentId="doc-1" onRouted={onRouted} />);

    await userEvent.click(screen.getByRole('button', { name: 'Mandant neu bestimmen' }));

    expect(await screen.findByText('Mandant gefunden')).toBeInTheDocument();
    expect(screen.getByText(/ACME GmbH/)).toBeInTheDocument();
    expect(screen.getByText(/95 %/)).toBeInTheDocument();
    await waitFor(() => expect(onRouted).toHaveBeenCalledTimes(1));
    expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/documents/doc-1/mandant-reroute'))).toBe(
      true,
    );
  });

  it('says that two clients match equally well instead of picking one', async () => {
    serve({
      ...ASSIGNED,
      status: 'AMBIGUOUS',
      mandantId: null,
      mandantName: null,
      confidence: null,
      candidates: [
        { mandantId: 'contact-1', mandantName: 'Alpha GmbH', confidence: 0.95 },
        { mandantId: 'contact-2', mandantName: 'Beta GmbH', confidence: 0.9 },
      ],
    });
    const onRouted = vi.fn();
    renderWithProviders(<MandantReroute documentId="doc-1" onRouted={onRouted} />);

    await userEvent.click(screen.getByRole('button', { name: 'Mandant neu bestimmen' }));

    expect(await screen.findByText('Nicht eindeutig')).toBeInTheDocument();
    expect(screen.getByText(/Alpha GmbH, Beta GmbH/)).toBeInTheDocument();
    expect(screen.getByText(/bleibt „Ohne Mandant“/)).toBeInTheDocument();
    expect(onRouted).not.toHaveBeenCalled();
  });

  it('says why a weak match is not enough', async () => {
    serve({
      ...ASSIGNED,
      status: 'BELOW_THRESHOLD',
      mandantId: null,
      mandantName: null,
      confidence: null,
      candidates: [{ mandantId: 'contact-1', mandantName: 'ACME GmbH', confidence: 0.8 }],
    });
    renderWithProviders(<MandantReroute documentId="doc-1" />);

    await userEvent.click(screen.getByRole('button', { name: 'Mandant neu bestimmen' }));

    expect(await screen.findByText('Zu unsicher')).toBeInTheDocument();
    expect(screen.getByText(/ACME GmbH mit 80 %/)).toBeInTheDocument();
    expect(screen.getByText(/unter der Schwelle von 90 %/)).toBeInTheDocument();
  });

  it('says when the document carries no signal at all', async () => {
    serve({
      ...ASSIGNED,
      status: 'NO_SIGNALS',
      mandantId: null,
      mandantName: null,
      confidence: null,
      signals: [],
      candidates: [],
    });
    renderWithProviders(<MandantReroute documentId="doc-1" />);

    await userEvent.click(screen.getByRole('button', { name: 'Mandant neu bestimmen' }));

    expect(await screen.findByText('Keine Signale')).toBeInTheDocument();
    expect(screen.getByText(/keine IBAN, USt-IdNr., Steuernummer oder Absender-Adresse/)).toBeInTheDocument();
  });

  it('reports that an existing assignment was left untouched', async () => {
    serve({ ...ASSIGNED, status: 'ALREADY_ASSIGNED', confidence: null });
    const onRouted = vi.fn();
    renderWithProviders(<MandantReroute documentId="doc-1" onRouted={onRouted} />);

    await userEvent.click(screen.getByRole('button', { name: 'Mandant neu bestimmen' }));

    expect(await screen.findByText('Bereits zugeordnet')).toBeInTheDocument();
    expect(screen.getByText(/nicht überschrieben/)).toBeInTheDocument();
    expect(onRouted).not.toHaveBeenCalled();
  });
});
