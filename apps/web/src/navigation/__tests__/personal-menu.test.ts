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

/** What a person with no special rights sees: the admin area is already filtered out. */
const available: readonly ScreenDefinition[] = screensVisibleFor(sidebarScreens, null);
const ids = (items: readonly ScreenDefinition[]): string[] => items.map((item) => item.id);

describe('personal menu layout', () => {
  it('keeps the standard menu by sections while nothing is stored', () => {
    const layout = layoutMenu(available, standardMenu);

    expect(layout.personalised).toBe(false);
    expect(ids(layout.main)).toEqual(ids(available));
    expect(layout.additional).toEqual([]);
    expect(layout.hiddenIds).toEqual([]);
  });

  it('applies the stored order and puts the rest behind it in registry order', () => {
    const layout = layoutMenu(available, { order: ['inbox', 'dashboard'], hidden: [] });

    expect(layout.personalised).toBe(true);
    expect(ids(layout.main).slice(0, 2)).toEqual(['inbox', 'dashboard']);
    // Nothing is lost: every available screen appears exactly once.
    expect([...ids(layout.main)].sort()).toEqual([...ids(available)].sort());
    expect(new Set(ids(layout.main)).size).toBe(ids(layout.main).length);
  });

  it('ignores ids the registry no longer knows, in both lists', () => {
    const layout = layoutMenu(available, {
      order: ['screen-that-was-removed', 'inbox'],
      hidden: ['another-unknown-screen'],
    });

    const expectedNext = ids(available).filter((id) => id !== 'inbox')[0];
    expect(ids(layout.main).slice(0, 2)).toEqual(['inbox', expectedNext]);
    expect(ids(layout.main)).not.toContain('screen-that-was-removed');
    expect(layout.additional).toEqual([]);
    expect(layout.hiddenIds).toEqual([]);
  });

  it('keeps an order that is entirely unknown as the standard menu', () => {
    const layout = layoutMenu(available, { order: ['gone'], hidden: [] });

    expect(layout.personalised).toBe(false);
    expect(ids(layout.main)).toEqual(ids(available));
  });

  it('groups hidden screens into "Weitere" by their registry section', () => {
    const layout = layoutMenu(available, { order: [], hidden: ['integrations', 'audit'] });

    expect(layout.personalised).toBe(false);
    expect(ids(layout.main)).not.toContain('integrations');
    expect(ids(layout.main)).not.toContain('audit');
    // Grouped, and in the section order of the registry — not in the order they were hidden.
    expect(layout.additional.map((group) => group.section.id)).toEqual(['automation', 'governance']);
    expect(ids(layout.additional[0]?.items ?? [])).toEqual(['integrations']);
    expect(ids(layout.additional[1]?.items ?? [])).toEqual(['audit']);
    expect(layout.hiddenIds).toEqual(['integrations', 'audit']);
  });

  it('treats a screen that is both ordered and hidden as hidden', () => {
    const layout = layoutMenu(available, { order: ['inbox', 'documents'], hidden: ['inbox'] });

    expect(ids(layout.main)).not.toContain('inbox');
    expect(layout.hiddenIds).toEqual(['inbox']);
    expect(ids(layout.main)[0]).toBe('documents');
  });

  it('never shows a screen the person may not use, even when the stored menu names it', () => {
    // The admin area is filtered out of `available` for everybody but the platform administrator.
    const layout = layoutMenu(
      available.filter((item) => item.id !== 'documents'),
      { order: ['documents', 'inbox'], hidden: ['documents'] },
    );

    expect(ids(layout.main)).not.toContain('documents');
    expect(layout.hiddenIds).not.toContain('documents');
    expect(ids(layout.main)[0]).toBe('inbox');
  });
});

describe('personal menu edits', () => {
  const firstIds = ids(available);

  it('hides a screen into the end of "Weitere"', () => {
    const next = hideFromMenu(firstIds, ['audit'], 'documents');

    expect(next.order).not.toContain('documents');
    expect(next.hidden).toEqual(['audit', 'documents']);
  });

  it('shows a hidden screen again at the end of the main list', () => {
    const next = showInMenu(firstIds, ['audit', 'integrations'], 'integrations');

    expect(next.hidden).toEqual(['audit']);
    expect(next.order[next.order.length - 1]).toBe('integrations');
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
