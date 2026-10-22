import type { KontoMapping } from '@/lib/api/types';

/**
 * The search of the account mapping screen.
 *
 * <p>The list is small — a Kanzlei has a few dozen suppliers, not thousands — so the search runs over what is
 * already on screen instead of asking the server for every keystroke. The backend supports the same filters
 * for other clients; the screen uses the one that answers immediately.</p>
 */
export function matchesKontoMappingQuery(mapping: KontoMapping, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === '') return true;
  return [
    mapping.mandantName,
    mapping.supplier,
    mapping.category,
    mapping.sollkonto,
    mapping.habenkonto,
    mapping.steuerschluessel,
  ].some((value) => (value ?? '').toLowerCase().includes(needle));
}

/** Applies the search field; the order the API sent is kept. */
export function filterKontoMappings(
  mappings: readonly KontoMapping[],
  query: string,
): readonly KontoMapping[] {
  return mappings.filter((mapping) => matchesKontoMappingQuery(mapping, query));
}

/**
 * A one-line name of a row, used where the full table is too much — the delete question says what is about
 * to disappear. The words for "all clients" and "all suppliers" come from the caller, because this module
 * must not depend on the interface language.
 */
export function kontoMappingLabel(
  mapping: KontoMapping,
  words: { readonly anyMandant: string; readonly anySupplier: string; readonly anyCategory: string },
): string {
  return [
    mapping.mandantName ?? words.anyMandant,
    mapping.supplier ?? words.anySupplier,
    mapping.category ?? words.anyCategory,
  ].join(' · ');
}
