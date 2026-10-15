import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { WorkspaceSwitcher } from '../workspace-switcher';

const calls: { url: string; method: string; body: string | null }[] = [];

function serve(list: object): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : null });
      const body = url.endsWith('/workspaces') && (init?.method ?? 'GET') === 'GET' ? list : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }),
  );
}

const two = {
  items: [
    { tenantId: 't1', name: 'Steuerbüro Weber', accountType: 'COMPANY', owner: true, planCode: 'PROFESSIONAL', current: true },
    { tenantId: 't2', name: 'Müller GmbH', accountType: 'COMPANY', owner: true, planCode: 'PROFESSIONAL', current: false },
  ],
  capabilities: { canCreateClients: true, professionalPlan: true },
};

describe('WorkspaceSwitcher', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    calls.length = 0;
  });

  it('lists the workspaces and tells the server which one to continue in', async () => {
    const user = userEvent.setup();
    serve(two);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    renderWithProviders(<WorkspaceSwitcher currentName="Steuerbüro Weber" />);

    await user.click(await screen.findByRole('button', { name: 'Arbeitsbereich wechseln' }));
    await user.click(await screen.findByRole('menuitem', { name: /Müller GmbH/ }));

    await waitFor(() => expect(calls.some((call) => call.url.includes('/workspaces/t2/switch') && call.method === 'POST')).toBe(true));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('/dashboard'));
  });

  it('offers a new client only to those who may, and creates it by name', async () => {
    const user = userEvent.setup();
    serve(two);
    vi.stubGlobal('location', { ...window.location, assign: vi.fn() });
    renderWithProviders(<WorkspaceSwitcher currentName="Steuerbüro Weber" />);

    await user.click(await screen.findByRole('button', { name: 'Arbeitsbereich wechseln' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Neuen Mandanten anlegen' }));
    await user.type(await screen.findByLabelText('Name des Mandanten (Firma)'), 'Schmidt KG');
    await user.click(screen.getByRole('button', { name: 'Anlegen' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/workspaces') && (call.body ?? '').includes('Schmidt KG'))).toBe(true));
  });

  it('is not shown for a person with a single workspace and no right to add clients', async () => {
    serve({ items: [two.items[0]], capabilities: { canCreateClients: false, professionalPlan: false } });
    const { container } = renderWithProviders(<WorkspaceSwitcher currentName="Weber" />);
    await waitFor(() => expect(calls.length).toBeGreaterThan(0));
    expect(container).toBeEmptyDOMElement();
  });
});
