import { describe, expect, it } from 'vitest';
import { contractVersionFromWire } from '../wire/finance';
import { templateSourceEntityFor, templateSourcePathFor } from '../wire/misc';

describe('contract template sources (V72)', () => {
  it('maps a contract placeholder to its source entity and path', () => {
    expect(templateSourceEntityFor('Vertrag.Title')).toBe('VERTRAG');
    expect(templateSourcePathFor('Vertrag.Title')).toBe('Title');
    expect(templateSourceEntityFor('Partei.Gegenpartei.Name')).toBe('PARTEI');
    expect(templateSourcePathFor('Partei.Gegenpartei.Name')).toBe('Gegenpartei.Name');
    expect(templateSourceEntityFor('Werk.Name')).toBe('WERK');
    expect(templateSourceEntityFor('Kunde.Email')).toBe('KUNDE');
  });

  it('treats everything outside the contract catalogue as a hand-filled field', () => {
    expect(templateSourceEntityFor('recipient_name')).toBe('CUSTOM');
    expect(templateSourceEntityFor('Betrag')).toBe('CUSTOM');
    expect(templateSourcePathFor('recipient_name')).toBe('');
  });

  it('maps a version with its bound document', () => {
    expect(
      contractVersionFromWire({
        id: 'v1',
        contractId: 'c1',
        versionLabel: 'V1',
        effectiveFrom: '2026-01-01',
        changeNote: 'Dokument aus Vorlage erzeugt',
        current: true,
        createdAt: '2026-10-15T08:00:00Z',
        documentId: 'doc-1',
      }),
    ).toMatchObject({ id: 'v1', versionLabel: 'V1', current: true, documentId: 'doc-1' });
    expect(contractVersionFromWire({ id: 'v2', contractId: 'c1', versionLabel: 'V2', current: false, createdAt: '' }).documentId).toBeNull();
  });
});
