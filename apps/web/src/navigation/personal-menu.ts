import { navSections, type NavSection, type ScreenDefinition } from './screens';

/**
 * The person's own menu, and how it is derived from the screen registry.
 *
 * <p>Nothing here talks to the API or to React: the same functions decide what the sidebar and the
 * command palette show, and the tests can reason about the order without a browser.</p>
 *
 * <p>The standard menu is what an account that never changed anything sees: the registry's sections,
 * in the registry's order. A stored order switches the main list to a flat, personal one — sections are
 * the product's suggestion, not the person's.</p>
 */

/** What is stored for one person. Empty arrays mean the standard menu. */
export interface MenuPreferences {
  readonly order: readonly string[];
  readonly hidden: readonly string[];
}

export const standardMenu: MenuPreferences = { order: [], hidden: [] };

/** Hidden screens of one registry section, as "Weitere" shows them. */
export interface MenuGroup {
  readonly section: NavSection;
  readonly items: readonly ScreenDefinition[];
}

export interface MenuLayout {
  /** The main list, in the person's order; the registry's order while nothing was stored. */
  readonly main: readonly ScreenDefinition[];
  /** True when a stored order applies: the main list is flat and section headings are not shown. */
  readonly personalised: boolean;
  /** The hidden screens, grouped by section; sections without hidden screens are omitted. */
  readonly additional: readonly MenuGroup[];
  /** The known hidden ids, in stored order (what "Weitere" contains, flattened). */
  readonly hiddenIds: readonly string[];
}

function resolveIds(
  ids: readonly string[],
  byId: ReadonlyMap<string, ScreenDefinition>,
): readonly string[] {
  const seen = new Set<string>();
  const resolved: string[] = [];
  for (const id of ids) {
    // An id the registry no longer knows is ignored: screens come and go, the stored menu outlives them.
    if (!byId.has(id) || seen.has(id)) continue;
    seen.add(id);
    resolved.push(id);
  }
  return resolved;
}

/**
 * Applies stored preferences to the screens the person may actually see.
 *
 * <p>`available` is already filtered by permission, plan and workspace kind. A screen that is not in it
 * is never shown — not in the main list and not in "Weitere" — even when the stored menu names it.</p>
 */
export function layoutMenu(
  available: readonly ScreenDefinition[],
  preferences: MenuPreferences,
): MenuLayout {
  // Keyed by string on purpose: the stored preference is a list of ids from an earlier build, and an id that
  // is no longer a ScreenId must simply find nothing rather than fail the type check.
  const byId = new Map<string, (typeof available)[number]>(available.map((item) => [item.id, item]));
  const ordered = resolveIds(preferences.order, byId);
  const hiddenIds = resolveIds(preferences.hidden, byId);
  const hiddenSet = new Set(hiddenIds);
  // A stored menu that both orders and hides a screen means "hidden": putting something away wins.
  const enteredOrder = ordered.filter((id) => !hiddenSet.has(id));
  const personalised = ordered.length > 0;

  const placed = new Set<string>([...enteredOrder, ...hiddenIds]);
  // A screen nobody placed yet stays reachable at the end of the main list instead of disappearing.
  const unplaced = available.map((item) => item.id).filter((id) => !placed.has(id));

  const mainIds = personalised
    ? [...enteredOrder, ...unplaced]
    : available.map((item) => item.id).filter((id) => !hiddenSet.has(id));
  const main = mainIds.flatMap((id) => {
    const item = byId.get(id);
    return item ? [item] : [];
  });

  const additional = navSections.flatMap((section) => {
    const items = available.filter((item) => item.section === section.id && hiddenSet.has(item.id));
    return items.length > 0 ? [{ section, items }] : [];
  });

  return { main, personalised, additional, hiddenIds };
}

/** The main order, whether or not the person has stored one: what "hide", "show" and "move" start from. */
export function mainOrderOf(layout: MenuLayout): readonly string[] {
  return layout.main.map((item) => item.id);
}

/** Hides one screen: it leaves the main list and goes to the end of "Weitere". */
export function hideFromMenu(
  mainOrder: readonly string[],
  hidden: readonly string[],
  id: string,
): MenuPreferences {
  return {
    order: mainOrder.filter((item) => item !== id),
    hidden: [...hidden.filter((item) => item !== id), id],
  };
}

/** Shows a hidden screen again: back into the main list, at its end. */
export function showInMenu(
  mainOrder: readonly string[],
  hidden: readonly string[],
  id: string,
): MenuPreferences {
  return {
    order: [...mainOrder.filter((item) => item !== id), id],
    hidden: hidden.filter((item) => item !== id),
  };
}

/** Moves a screen one place up or down. Out-of-range moves leave the order untouched. */
export function moveInMenu(
  mainOrder: readonly string[],
  id: string,
  delta: number,
): readonly string[] {
  const index = mainOrder.indexOf(id);
  const target = index + delta;
  const current = mainOrder[index];
  const neighbour = mainOrder[target];
  if (index < 0 || current === undefined || neighbour === undefined) return mainOrder;
  const next = [...mainOrder];
  next[index] = neighbour;
  next[target] = current;
  return next;
}

/**
 * Moves a dragged screen next to the one it was dropped on. Dropping on itself, on an unknown target or
 * with an unknown id is a no-op, so a stale drag can never corrupt the order.
 */
export function reorderInMenu(
  mainOrder: readonly string[],
  draggedId: string,
  targetId: string,
  position: 'before' | 'after',
): readonly string[] {
  if (draggedId === targetId) return mainOrder;
  if (!mainOrder.includes(draggedId) || !mainOrder.includes(targetId)) return mainOrder;
  const without = mainOrder.filter((id) => id !== draggedId);
  const targetIndex = without.indexOf(targetId);
  if (targetIndex < 0) return mainOrder;
  const insertAt = position === 'before' ? targetIndex : targetIndex + 1;
  return [...without.slice(0, insertAt), draggedId, ...without.slice(insertAt)];
}
