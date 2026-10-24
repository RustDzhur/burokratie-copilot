import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, screen, waitFor } from '@/test/render';
import { api } from '@/lib/api';
import { CalendarCard } from '../calendar-card';

vi.mock('@/lib/api', () => ({
  api: {
    calendar: {
      feeds: { list: vi.fn().mockResolvedValue([]), create: vi.fn(), revoke: vi.fn() },
      connection: { status: vi.fn(), connect: vi.fn(), disconnect: vi.fn(), discover: vi.fn(), sync: vi.fn() },
    },
  },
}));

const connection = api.calendar.connection as unknown as {
  status: ReturnType<typeof vi.fn>;
  sync: ReturnType<typeof vi.fn>;
};

describe('CalendarCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('keeps the phone feed and offers connecting an external calendar while none is connected', async () => {
    connection.status.mockResolvedValue(null);

    renderWithProviders(<CalendarCard />);

    // The feed part stays.
    expect(screen.getByText(/Mit Handy verbinden/)).toBeInTheDocument();
    // The CalDAV part offers a connection and says so honestly.
    await waitFor(() =>
      expect(screen.getByText(/Noch kein eigener Kalender verbunden/)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /Kalender verbinden/ })).toBeInTheDocument();
  });

  it('shows the connected account, its error and the sync action', async () => {
    connection.status.mockResolvedValue({
      id: 'c1',
      provider: 'icloud',
      account: 'person@icloud.com',
      serverUrl: 'https://caldav.icloud.com',
      calendarPath: '/12345/calendars/AAA/',
      calendarName: 'Arbeit',
      status: 'ERROR',
      lastSyncAt: null,
      lastSyncStatus: 'FAILED',
      error: 'Das App-Passwort oder das Konto ist nicht korrekt',
    });

    renderWithProviders(<CalendarCard />);

    await waitFor(() => expect(screen.getByText('person@icloud.com')).toBeInTheDocument());
    expect(screen.getByText(/Kalender: Arbeit/)).toBeInTheDocument();
    expect(screen.getByText(/Das App-Passwort oder das Konto ist nicht korrekt/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Jetzt synchronisieren/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Verbindung trennen/ })).toBeInTheDocument();
  });
});
