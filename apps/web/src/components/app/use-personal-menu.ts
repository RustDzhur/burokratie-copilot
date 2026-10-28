'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useSession } from '@/lib/auth/session-context';
import { useToast } from '@/components/ui/toast';
import {
  hideFromMenu,
  layoutMenu,
  mainOrderOf,
  moveInMenu,
  reorderInMenu,
  showInMenu,
  standardMenu,
  type MenuLayout,
  type MenuPreferences,
} from '@/navigation/personal-menu';
import { screensVisibleFor, sidebarScreens } from '@/navigation/screens';

/**
 * How long the menu waits before it writes a change. Dragging a screen three places is one request, not
 * three, and a person who clicks the arrows quickly does not flood the endpoint either.
 */
export const MENU_SAVE_DELAY_MS = 600;

export interface PersonalMenu {
  /** What the sidebar renders: the main list and the "Weitere" groups. */
  readonly layout: MenuLayout;
  /** `true` while a change is on its way to the server (the edit mode shows a quiet hint). */
  readonly isSaving: boolean;
  readonly hide: (id: string) => void;
  readonly show: (id: string) => void;
  readonly move: (id: string, delta: number) => void;
  readonly drop: (draggedId: string, targetId: string, position: 'before' | 'after') => void;
  /** Back to the standard menu by sections; a failure leaves the menu as it was. */
  readonly reset: () => Promise<void>;
}

/**
 * Loads, edits and persists the person's own menu.
 *
 * <p>The standard menu is rendered immediately and stays if the preference cannot be loaded: a menu
 * that is not personalised is always better than no menu at all. Every edit is applied locally first
 * (the sidebar must feel immediate), written after a short pause, and rolled back with a message when
 * the server refuses it.</p>
 *
 * <p>The hook never calls the API without a session: a menu preference belongs to a person, and an
 * anonymous sidebar has nothing to personalise.</p>
 */
export function usePersonalMenu(): PersonalMenu {
  const session = useSession();
  const t = useTranslations('nav');
  const tErrors = useTranslations('errors');
  const toast = useToast();

  const available = useMemo(() => screensVisibleFor(sidebarScreens, session), [session]);
  const [preferences, setPreferences] = useState<MenuPreferences>(standardMenu);
  const [isSaving, setIsSaving] = useState(false);

  const layout = useMemo(() => layoutMenu(available, preferences), [available, preferences]);

  /** What the server last confirmed; the state a failed save falls back to. */
  const savedRef = useRef<MenuPreferences>(standardMenu);
  const timerRef = useRef<number | null>(null);
  /** Makes sure a slow answer to an older edit cannot overwrite a newer one. */
  const requestRef = useRef(0);
  const mountedRef = useRef(true);
  const userId = session?.id;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // Load once per person. A failed request keeps the standard menu — never a blank or broken one.
  useEffect(() => {
    if (!userId) {
      savedRef.current = standardMenu;
      setPreferences(standardMenu);
      return undefined;
    }
    const controller = new AbortController();
    let active = true;
    api.navigation
      .get(controller.signal)
      .then((stored) => {
        if (!active) return;
        const next: MenuPreferences = { order: stored.order, hidden: stored.hidden };
        savedRef.current = next;
        setPreferences(next);
      })
      .catch(() => {
        // No preference (or no connection): the standard menu is the honest answer, not an error screen.
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [userId]);

  // Drop a pending write when the person changes (sign-out) so it cannot land on the next session.
  useEffect(
    () => () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    },
    [userId],
  );

  const persist = useCallback(
    async (next: MenuPreferences) => {
      const request = requestRef.current + 1;
      requestRef.current = request;
      setIsSaving(true);
      try {
        await api.navigation.save({ order: next.order, hidden: next.hidden });
        if (request !== requestRef.current) return;
        savedRef.current = next;
      } catch (error) {
        if (request !== requestRef.current || !mountedRef.current) return;
        // The order the server last confirmed is what the sidebar shows again, together with the reason.
        setPreferences(savedRef.current);
        toast.error(t('customise.saveFailed'), tErrors(toApiError(error).kind));
      } finally {
        if (request === requestRef.current && mountedRef.current) setIsSaving(false);
      }
    },
    [t, tErrors, toast],
  );

  const scheduleSave = useCallback(
    (next: MenuPreferences) => {
      if (typeof window === 'undefined' || !userId) return;
      if (timerRef.current !== null) window.clearTimeout(timerRef.current);
      timerRef.current = window.setTimeout(() => {
        timerRef.current = null;
        void persist(next);
      }, MENU_SAVE_DELAY_MS);
    },
    [persist, userId],
  );

  const apply = useCallback(
    (next: MenuPreferences) => {
      setPreferences(next);
      scheduleSave(next);
    },
    [scheduleSave],
  );

  const hide = useCallback(
    (id: string) => apply(hideFromMenu(mainOrderOf(layout), layout.hiddenIds, id)),
    [apply, layout],
  );

  const show = useCallback(
    (id: string) => apply(showInMenu(mainOrderOf(layout), layout.hiddenIds, id)),
    [apply, layout],
  );

  const move = useCallback(
    (id: string, delta: number) =>
      apply({ order: moveInMenu(mainOrderOf(layout), id, delta), hidden: layout.hiddenIds }),
    [apply, layout],
  );

  const drop = useCallback(
    (draggedId: string, targetId: string, position: 'before' | 'after') =>
      apply({
        order: reorderInMenu(mainOrderOf(layout), draggedId, targetId, position),
        hidden: layout.hiddenIds,
      }),
    [apply, layout],
  );

  const reset = useCallback(async () => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    // A write that is already on its way must not re-create the menu after the reset.
    requestRef.current += 1;
    if (!userId) {
      savedRef.current = standardMenu;
      setPreferences(standardMenu);
      return;
    }
    try {
      await api.navigation.reset();
      if (!mountedRef.current) return;
      savedRef.current = standardMenu;
      setPreferences(standardMenu);
      toast.success(t('customise.resetDone'));
    } catch (error) {
      if (!mountedRef.current) return;
      toast.error(t('customise.resetFailed'), tErrors(toApiError(error).kind));
    }
  }, [t, tErrors, toast, userId]);

  return { layout, isSaving, hide, show, move, drop, reset };
}
