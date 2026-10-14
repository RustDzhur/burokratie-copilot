'use client';

import { useState } from 'react';
import { CalendarFeedDialog } from '@/components/app/calendar-feed-dialog';
import { Card, CardBody, CardHeader } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { useTranslations } from '@/i18n/provider';
import styles from './integrations.module.css';

/** The calendar of the phone: Google Calendar and the iPhone calendar subscribe to a private address of the platform. */
export function CalendarCard() {
  const t = useTranslations('integrations');
  const [open, setOpen] = useState(false);
  return (
    <section className={styles.section} aria-labelledby="calendar-heading">
      <Card>
        <CardHeader
          title={t('calendar.title')}
          description={t('calendar.description')}
          titleId="calendar-heading"
          headingLevel={2}
        />
        <CardBody>
          <ul className={styles.meta}>
            <li>{t('calendar.iphone')}</li>
            <li>{t('calendar.google')}</li>
            <li>{t('calendar.android')}</li>
          </ul>
          <p className={styles.meta}>{t('calendar.note')}</p>
          <Button variant="primary" onClick={() => setOpen(true)}>
            {t('calendar.connect')}
          </Button>
        </CardBody>
      </Card>
      <CalendarFeedDialog open={open} onClose={() => setOpen(false)} />
    </section>
  );
}
