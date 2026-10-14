'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { CalendarFeedDialog } from '@/components/app/calendar-feed-dialog';
import { CreateRecordDialog } from '@/components/app/create-record-dialog';
import { Badge, Card, CardBody, EmptyState, StatusPill, Tabs } from '@/components/ui';
import { Dialog } from '@/components/ui/overlay';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonTable } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import {
  addMonths,
  buildMonthMatrix,
  formatDate,
  isToday,
  startOfMonth,
  toIsoDate,
} from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { CalendarEvent } from '@/lib/api/types';
import { useGroupedLabels } from '@/lib/format/status';
import styles from './calendar.module.css';

const TYPE_VALUES = ['deadline', 'appointment', 'reminder'] as const;
const WEEKDAY_LABELS = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'] as const;

/**
 * Calendar and deadlines (§5.2.11).
 *
 * Only real backend events are rendered. Public holidays are explicitly *not*
 * assumed: the notice points at the configurable holiday calendar instead
 * (docs/task.md §24 — "Feiertage через настраиваемый календарь").
 */
export default function CalendarPage() {
  const t = useTranslations('calendar');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const toast = useToast();
  const typeLabel = useGroupedLabels('calendar', 'type');

  const [month, setMonth] = useState<Date>(startOfMonth(new Date()));
  const [selectedDate, setSelectedDate] = useState<string>(toIsoDate(new Date()));
  const [view, setView] = useState<'month' | 'week' | 'list'>('month');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [openEvent, setOpenEvent] = useState<CalendarEvent | null>(null);
  const [feedOpen, setFeedOpen] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  const range = useMemo(() => {
    const weeks = buildMonthMatrix(month);
    const first = weeks[0]?.[0] ?? month;
    const lastWeek = weeks[weeks.length - 1];
    const last = lastWeek?.[6] ?? month;
    return { from: toIsoDate(first), to: toIsoDate(last) };
  }, [month]);

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.calendar.events({ from: range.from, to: range.to, size: 200 }, signal),
    [range.from, range.to, reloadToken],
    { keepPreviousData: true },
  );

  const events = useMemo(() => (state.status === 'ready' ? state.data.items : []), [state]);
  const eventsByDate = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>();
    for (const event of events) {
      const list = map.get(event.date) ?? [];
      list.push(event);
      map.set(event.date, list);
    }
    return map;
  }, [events]);

  const listEvents = useMemo(() => {
    const sorted = [...events].sort((a, b) => a.date.localeCompare(b.date));
    if (view === 'week') {
      const selected = new Date(selectedDate);
      const day = (selected.getDay() + 6) % 7;
      const start = new Date(selected);
      start.setDate(start.getDate() - day);
      const end = new Date(start);
      end.setDate(end.getDate() + 6);
      return sorted.filter(
        (event) => event.date >= toIsoDate(start) && event.date <= toIsoDate(end),
      );
    }
    return sorted;
  }, [events, selectedDate, view]);

  const createAppointment = async (values: Record<string, string>): Promise<void> => {
    await api.calendar.create({
      title: values.title ?? '',
      date: values.date ?? toIsoDate(new Date()),
      type: (values.type ?? 'appointment') as CalendarEvent['type'],
    });
    toast.success(tCommon('actions.create'));
    setReloadToken((value) => value + 1);
  };

  return (
    <>
      <PageHeader
        title={tNav('items.calendar')}
        subtitle={t('subtitle')}
        actions={
          <>
            <Button variant="secondary" iconLeft="refresh" onClick={reload} loading={isRefreshing}>
              {tCommon('actions.refresh')}
            </Button>
            <Button variant="secondary" iconLeft="bell" onClick={() => setFeedOpen(true)}>
              {t('action.connectPhone')}
            </Button>
            <Button variant="primary" iconLeft="plus" onClick={() => setDialogOpen(true)}>
              {t('action.new')}
            </Button>
          </>
        }
      />
      <CalendarFeedDialog open={feedOpen} onClose={() => setFeedOpen(false)} />

      <div className={styles.toolbar}>
        <div className={styles.monthNav}>
          <Button
            variant="ghost"
            iconLeft="chevron-left"
            onClick={() => setMonth(addMonths(month, -1))}
            aria-label={tCommon('actions.previous')}
          >
            {tCommon('actions.previous')}
          </Button>
          <p className={styles.monthLabel} aria-live="polite">
            {formatDate(month, locale, 'long')}
          </p>
          <Button
            variant="ghost"
            iconRight="chevron-right"
            onClick={() => setMonth(addMonths(month, 1))}
            aria-label={tCommon('actions.next')}
          >
            {tCommon('actions.next')}
          </Button>
          <Button
            variant="secondary"
            onClick={() => {
              setMonth(startOfMonth(new Date()));
              setSelectedDate(toIsoDate(new Date()));
            }}
          >
            {t('today')}
          </Button>
        </div>
      </div>

      <Tabs
        label={tNav('items.calendar')}
        value={view}
        onChange={(value) => setView(value as 'month' | 'week' | 'list')}
        tabs={[
          {
            id: 'month',
            label: t('view.month'),
            content: (
              <ResourceView
                state={state}
                onRetry={reload}
                isRefreshing={isRefreshing}
                skeleton={<SkeletonTable rows={6} columns={7} />}
                isEmpty={(data) => data.items.length === 0}
                emptyState={
                  <EmptyState
                    icon="calendar"
                    title={t('empty.title')}
                    description={t('empty.description')}
                    actions={
                      <Button variant="primary" iconLeft="plus" onClick={() => setDialogOpen(true)}>
                        {t('empty.action')}
                      </Button>
                    }
                  />
                }
              >
                {() => (
                  <table className={styles.monthGrid}>
                    <caption className={styles.visuallyHidden}>
                      {formatDate(month, locale, 'long')}
                    </caption>
                    <thead>
                      <tr>
                        {WEEKDAY_LABELS.map((label) => (
                          <th key={label} scope="col">
                            {label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {buildMonthMatrix(month).map((week) => (
                        <tr key={toIsoDate(week[0] ?? month)}>
                          {week.map((day) => {
                            const iso = toIsoDate(day);
                            const dayEvents = eventsByDate.get(iso) ?? [];
                            const outside = day.getMonth() !== month.getMonth();
                            return (
                              <td key={iso} className={outside ? styles.dayOutside : undefined}>
                                <div
                                  className={[
                                    styles.dayCell,
                                    iso === selectedDate ? styles.daySelected : '',
                                    isToday(day) ? styles.dayToday : '',
                                  ]
                                    .filter(Boolean)
                                    .join(' ')}
                                >
                                  <button
                                    type="button"
                                    className={styles.dayNumberButton}
                                    onClick={() => setSelectedDate(iso)}
                                    aria-pressed={iso === selectedDate}
                                  >
                                    <span className={styles.dayNumber}>{day.getDate()}</span>
                                  </button>
                                  <span className={styles.dayEvents}>
                                    {dayEvents.slice(0, 3).map((event) => (
                                      <button
                                        type="button"
                                        key={event.id}
                                        className={styles.eventChip}
                                        title={event.title}
                                        onClick={() => setOpenEvent(event)}
                                      >
                                        {event.title}
                                      </button>
                                    ))}
                                    {dayEvents.length > 3 ? (
                                      <button
                                        type="button"
                                        className={styles.moreEvents}
                                        onClick={() => setSelectedDate(iso)}
                                      >
                                        {t('moreEvents', { count: dayEvents.length - 3 })}
                                      </button>
                                    ) : null}
                                  </span>
                                </div>
                              </td>
                            );
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </ResourceView>
            ),
          },
          {
            id: 'week',
            label: t('view.week'),
            content: (
              <ResourceView
                state={state}
                onRetry={reload}
                isEmpty={() => listEvents.length === 0}
                emptyState={
                  <EmptyState
                    icon="calendar"
                    title={t('empty.title')}
                    description={t('empty.description')}
                  />
                }
              >
                {() => (
                  <EventList
                    events={listEvents}
                    typeLabel={typeLabel}
                    locale={locale}
                    caption={t('view.week')}
                    onOpen={setOpenEvent}
                  />
                )}
              </ResourceView>
            ),
          },
          {
            id: 'list',
            label: t('view.list'),
            content: (
              <ResourceView
                state={state}
                onRetry={reload}
                isEmpty={() => listEvents.length === 0}
                emptyState={
                  <EmptyState
                    icon="calendar"
                    title={t('empty.title')}
                    description={t('empty.description')}
                  />
                }
              >
                {() => (
                  <EventList
                    events={listEvents}
                    typeLabel={typeLabel}
                    locale={locale}
                    caption={t('view.list')}
                    onOpen={setOpenEvent}
                  />
                )}
              </ResourceView>
            ),
          },
        ]}
      />

      {view === 'month' && (eventsByDate.get(selectedDate) ?? []).length > 0 ? (
        <PageSection title={`${t('selectedDay')}: ${formatDate(selectedDate, locale)}`}>
          <ul className={styles.dayList}>
            {(eventsByDate.get(selectedDate) ?? []).map((event) => (
              <li key={event.id}>
                <Button variant="ghost" onClick={() => setOpenEvent(event)}>
                  {event.title}
                </Button>
              </li>
            ))}
          </ul>
        </PageSection>
      ) : null}

      <Dialog
        open={openEvent !== null}
        onClose={() => setOpenEvent(null)}
        title={openEvent?.title ?? ''}
        description={openEvent ? `${formatDate(openEvent.date, locale)} · ${typeLabel(openEvent.type)}` : undefined}
      >
        {openEvent ? (
          <div className={styles.eventDetail}>
            <p className={styles.eventNote}>{openEvent.note ?? t('detail.noNote')}</p>
            {openEvent.origin === 'task-deadline' ? (
              <p className={styles.eventHint}>{t('detail.automatic')}</p>
            ) : null}
            <p className={styles.eventLinks}>
              {openEvent.documentId ? (
                <Link href={`/documents/${openEvent.documentId}`}>{t('detail.openDocument')}</Link>
              ) : null}
              {openEvent.taskId ? <Link href="/tasks">{t('detail.openTask')}</Link> : null}
              {openEvent.caseId ? <Link href="/cases">{t('detail.openCase')}</Link> : null}
            </p>
          </div>
        ) : null}
      </Dialog>

      <PageSection title={t('legend.title')}>
        <InlineAlert tone="info">{t('holidays.disclaimer')}</InlineAlert>
      </PageSection>

      <CreateRecordDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title={t('action.new')}
        submitLabel={tCommon('actions.create')}
        fields={[
          { name: 'title', label: t('column.title'), required: true },
          { name: 'date', label: t('column.date'), type: 'date', required: true },
          {
            name: 'type',
            label: t('column.type'),
            type: 'select',
            // Deadlines belong to tasks and cases and appear here from there; only appointments and
            // reminders are entered by hand.
            options: TYPE_VALUES.filter((value) => value !== 'deadline').map((value) => ({
              value,
              label: typeLabel(value),
            })),
          },
        ]}
        onSubmit={createAppointment}
      />
    </>
  );
}

function EventList({
  events,
  typeLabel,
  locale,
  caption,
  onOpen,
}: {
  events: readonly CalendarEvent[];
  typeLabel: (value: string) => string;
  locale: 'de' | 'en' | 'ru';
  caption: string;
  onOpen: (event: CalendarEvent) => void;
}) {
  const t = useTranslations('calendar');
  return (
    <Card padding="none">
      <CardBody>
        <table className={styles.listTable}>
          <caption className={styles.visuallyHidden}>{caption}</caption>
          <thead>
            <tr>
              <th scope="col">{t('column.date')}</th>
              <th scope="col">{t('column.title')}</th>
              <th scope="col">{t('column.type')}</th>
              <th scope="col">{t('column.case')}</th>
            </tr>
          </thead>
          <tbody>
            {events.map((event) => (
              <tr key={event.id}>
                <td>{formatDate(event.date, locale)}</td>
                <td>
                  <Button variant="ghost" onClick={() => onOpen(event)}>
                    {event.title}
                  </Button>
                </td>
                <td>
                  <StatusPill
                    label={typeLabel(event.type)}
                    tone={event.type === 'deadline' ? 'warning' : 'info'}
                  />
                </td>
                <td>
                  {event.caseReference ? (
                    <Badge tone="neutral" variant="outline">
                      {event.caseReference}
                    </Badge>
                  ) : (
                    '—'
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </CardBody>
    </Card>
  );
}
