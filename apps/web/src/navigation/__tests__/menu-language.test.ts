import { describe, expect, it } from 'vitest';
import { catalogues } from '@/i18n/messages';
import { navSections, screensBySection, sidebarScreens } from '@/navigation/screens';

/**
 * The specification forbids system language on the first screen (§6.3) and in the whole menu (§11.3), and the
 * glossary (§7) names the words the office actually uses: Beleg, Mandant, Vorgang, Frist, Freigabe.
 *
 * <p>This test exists because wording erodes silently. Nobody decides to put "Workflow" back into a menu - it
 * arrives in a pull request that is about something else.</p>
 */

/** Words that describe how software is built, not what a bookkeeper does. */
const SYSTEM_WORDS = [
  'API',
  'Workflow',
  'Integration',
  'Dashboard',
  'Feature',
  'Modul',
  'Sync',
  'Pipeline',
  'Kontingent',
];

describe('menu language', () => {
  it('names no technical concept in the German menu', () => {
    const hits: string[] = [];
    for (const screen of sidebarScreens) {
      const label = catalogues.de.nav[screen.titleKey];
      expect(label, `missing German label for ${screen.id}`).toBeTruthy();
      for (const word of SYSTEM_WORDS) {
        if (label.toLowerCase().includes(word.toLowerCase())) {
          hits.push(`${screen.id}: "${label}" contains "${word}"`);
        }
      }
    }
    expect(hits).toEqual([]);
  });

  it('names each group after the office, not the software', () => {
    for (const section of navSections) {
      const label = catalogues.de.nav[section.labelKey];
      expect(label, `missing German label for ${section.id}`).toBeTruthy();
      for (const word of SYSTEM_WORDS) {
        expect(label.toLowerCase()).not.toContain(word.toLowerCase());
      }
      // A group with no items is a heading that promises nothing.
      expect(screensBySection(section.id).filter((s) => s.inSidebar).length).toBeGreaterThan(0);
    }
  });
});
