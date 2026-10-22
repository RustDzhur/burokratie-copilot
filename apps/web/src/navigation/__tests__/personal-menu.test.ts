import { describe, expect, it } from 'vitest';
import { screensVisibleFor, sidebarScreens, type ScreenDefinition } from '../screens';
import {
  hideFromMenu,
  layoutMenu,
  mainOrderOf,
  moveInMenu,
  reorderInMenu,
  showInMenu,
  standardMenu,
} from '../personal-menu';

/**
 * The Kanzlei menu: ten items in three groups. The personal menu can only rearrange and hide these
 * ten; a module that is not in the menu (§4.3: removed, not tucked away) cannot be brought back by a
 * stored preference either.
 */
const available: readonly ScreenDefinition[] = screensVisibleFor(sidebarScreens, null);
const ids = (items: readonly ScreenDefinition[]): string[] => items.map((item) => item.id);
const firstIds = ids(available);

describe('personal menu layout', () => {
  it('keeps the standard menu by sections while nothing is stored', () => {
    const layout = layoutMenu(available, standardMenu);

    expect(layout.personalised).toBe(false);
    expect(ids(layout.main)).toEqual(firstIds);
    expect(layout.additional).toEqual([]);
    expect(layout.hiddenIds).toEqual([]);
    expect(firstIds).toEqual([
      'documents',
      'cases',
      'approvals',
      'calendar',
      'invoices',
      'contacts',
      'templates',
      'reports',
      'exports',
      'archive',
      'accountSettings',
    ]);
  });

  it('applies the stored order and puts the rest behind it in registry order', () => {
    const layout = layoutMenu(available, { order: ['calendar', 'documents'], hidden: [] });

    expect(layout.personalised).toBe(true);
    expect(ids(layout.main).slice(0, 2)).toEqual(['calendar', 'documents']);
    // Nothing is lost: every available screen appears exactly once.
    expect([...ids(layout.main)].sort()).toEqual([...firstIds].sort());
    expect(new Set(ids(layout.main)).size).toBe(ids(layout.main).length);
  });

  it('ignores ids the registry no longer knows, in both lists', () => {
    const layout = layoutMenu(available, {
      order: ['screen-that-was-removed', 'documents'],
      hidden: ['another-unknown-screen'],
    });

    const expectedNext = firstIds.filter((id) => id !== 'documents')[0];
    expect(ids(layout.main).slice(0, 2)).toEqual(['documents', expectedNext]);
    expect(ids(layout.main)).not.toContain('screen-that-was-removed');
    expect(layout.additional).toEqual([]);
    expect(layout.hiddenIds).toEqual([]);
  });

  it('keeps an order that is entirely unknown as the standard menu', () => {
    const layout = layoutMenu(available, { order: ['gone'], hidden: [] });

    expect(layout.personalised).toBe(false);
    expect(ids(layout.main)).toEqual(firstIds);
  });

  it('groups hidden screens into "Weitere" by their registry section', () => {
    const layout = layoutMenu(available, { order: [], hidden: ['reports', 'documents'] });

    expect(layout.personalised).toBe(false);
    expect(ids(layout.main)).not.toContain('documents');
    expect(ids(layout.main)).not.toContain('reports');
    // Grouped by the section they belong to, in the registry's section order; not in the order they were
    // hidden and not in the order the stored array happens to have.
    expect(layout.additional.map((group) => group.section.id)).toEqual(['arbeit', 'verwaltung']);
    expect(ids(layout.additional[0]?.items ?? [])).toEqual(['documents']);
    expect(ids(layout.additional[1]?.items ?? [])).toEqual(['reports']);
    expect(layout.hiddenIds).toEqual(['reports', 'documents']);
  });

  it('treats a screen that is both ordered and hidden as hidden', () => {
    const layout = layoutMenu(available, { order: ['cases', 'documents'], hidden: ['cases'] });

    expect(ids(layout.main)).not.toContain('cases');
    expect(layout.hiddenIds).toEqual(['cases']);
    expect(ids(layout.main)[0]).toBe('documents');
  });

  it('never shows a screen that is not part of the menu, even when the stored menu names it', () => {
    // A module removed from the menu (documents here, standing in for a removed module) stays gone: the
    // personal preferences cannot bring it back, no matter what was stored earlier.
    const layout = layoutMenu(
      available.filter((item) => item.id !== 'documents'),
      { order: ['documents', 'cases'], hidden: ['documents'] },
    );

    expect(ids(layout.main)).not.toContain('documents');
    expect(layout.hiddenIds).not.toContain('documents');
    expect(ids(layout.main)[0]).toBe('cases');
  });
});

describe('personal menu edits', () => {
  it('hides a screen into the end of "Weitere"', () => {
    const next = hideFromMenu(firstIds, ['reports'], 'documents');

    expect(next.order).not.toContain('documents');
    expect(next.hidden).toEqual(['reports', 'documents']);
  });

  it('shows a hidden screen again at the end of the main list', () => {
    const next = showInMenu(firstIds, ['reports', 'invoices'], 'invoices');

    expect(next.hidden).toEqual(['reports']);
    expect(next.order[next.order.length - 1]).toBe('invoices');
  });

  it('moves a screen one place up or down and refuses to leave the list', () => {
    const order = ['a', 'b', 'c'];

    expect(moveInMenu(order, 'b', -1)).toEqual(['b', 'a', 'c']);
    expect(moveInMenu(order, 'b', 1)).toEqual(['a', 'c', 'b']);
    expect(moveInMenu(order, 'a', -1)).toEqual(order);
    expect(moveInMenu(order, 'c', 1)).toEqual(order);
    expect(moveInMenu(order, 'gone', 1)).toEqual(order);
  });

  it('drops a dragged screen before or after the target', () => {
    const order = ['a', 'b', 'c', 'd'];

    expect(reorderInMenu(order, 'd', 'a', 'before')).toEqual(['d', 'a', 'b', 'c']);
    expect(reorderInMenu(order, 'a', 'c', 'after')).toEqual(['b', 'c', 'a', 'd']);
    expect(reorderInMenu(order, 'a', 'a', 'before')).toEqual(order);
    expect(reorderInMenu(order, 'gone', 'a', 'before')).toEqual(order);
  });

  it('exposes the current main order for the edit actions', () => {
    expect(mainOrderOf(layoutMenu(available, standardMenu))).toEqual(firstIds);
  });
});
