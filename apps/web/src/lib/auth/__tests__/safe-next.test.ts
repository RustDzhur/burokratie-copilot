import { describe, expect, it } from 'vitest';
import { safeNextPath } from '../safe-next';

describe('safeNextPath', () => {
  it('accepts a path inside the application, with its query', () => {
    expect(safeNextPath('/documents/42')).toBe('/documents/42');
    expect(safeNextPath('/tasks?status=doing')).toBe('/tasks?status=doing');
  });

  it.each([
    ['//evil.example'],
    ['https://evil.example'],
    ['/\\evil.example'],
    ['javascript:alert(1)'],
    ['/login'],
    ['/register?x=1'],
    ['/a\nb'],
    [''],
  ])('refuses %j', (value) => {
    expect(safeNextPath(value)).toBeNull();
  });

  it('refuses a missing value', () => {
    expect(safeNextPath(null)).toBeNull();
    expect(safeNextPath(undefined)).toBeNull();
  });
});
