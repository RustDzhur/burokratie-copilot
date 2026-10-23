import { describe, expect, it } from 'vitest';
import { sessionUserFromMe } from '../resources';

function me(permissions?: readonly string[]): Parameters<typeof sessionUserFromMe>[0] {
  return {
    userId: 'u1',
    tenantId: 't1',
    email: 'nina@example.de',
    displayName: 'Nina Test',
    workspaceName: 'Testkabinett',
    roles: ['OWNER'],
    permissions,
  };
}

describe('sessionUserFromMe', () => {
  it('carries the permissions the server granted, so screens can follow settings.view', () => {
    const user = sessionUserFromMe(me(['settings.view', 'settings.manage_rules']));
    expect(user.permissions).toEqual(['settings.view', 'settings.manage_rules']);
    expect(user.platformAdmin).toBe(false);
  });

  it('treats a missing permission list as empty instead of inventing a right', () => {
    expect(sessionUserFromMe(me()).permissions).toEqual([]);
  });
});
