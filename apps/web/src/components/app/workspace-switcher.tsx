'use client';

import { useState } from 'react';
import { Icon } from '@/components/icons';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui';
import { Input } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import { DropdownMenu, type MenuItemDefinition } from './dropdown-menu';

/**
 * The workspaces of the signed-in person, one per company: an accountant with ten clients switches between ten separate
 * workspaces instead of filtering one pile. Shown only when there is more than one workspace or the person may add clients.
 * After a switch the page is loaded anew, so nothing of the previous workspace stays on screen.
 */
export function WorkspaceSwitcher({ currentName }: { readonly currentName: string }) {
  const t = useTranslations('common');
  const toast = useToast();
  const { state, reload } = useApiResource((signal) => api.workspaces.list(signal), []);
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);

  if (state.status !== 'ready') return null;
  const { items, capabilities } = state.data;
  // Only on the Professional plan; below it the server sends no workspaces and no right to add clients.
  if (!capabilities.professionalPlan) return null;
  if (items.length < 2 && !capabilities.canCreateClients) return null;

  const switchTo = async (tenantId: string): Promise<void> => {
    try {
      await api.workspaces.switchTo(tenantId);
      window.location.assign('/dashboard');
    } catch (error) {
      toast.error(t('switcher.switchFailed'), toApiError(error).message);
    }
  };

  const create = async (): Promise<void> => {
    setBusy(true);
    try {
      const created = await api.workspaces.createClient(name.trim());
      setAdding(false);
      setName('');
      reload();
      await switchTo(created.tenantId);
    } catch (error) {
      toast.error(t('switcher.createFailed'), toApiError(error).message);
    } finally {
      setBusy(false);
    }
  };

  const menu: MenuItemDefinition[] = [
    ...items.map((item) => ({
      id: item.tenantId,
      label: item.name,
      icon: 'briefcase' as const,
      checked: item.current,
      onSelect: () => {
        if (!item.current) void switchTo(item.tenantId);
      },
    })),
    ...(capabilities.canCreateClients
      ? [{ id: 'add', label: t('switcher.add'), icon: 'plus' as const, onSelect: () => setAdding(true) }]
      : []),
  ];

  return (
    <>
      <DropdownMenu
        label={t('switcher.menu')}
        align="start"
        items={menu}
        trigger={
          <button type="button" data-tour="switcher" aria-label={t('switcher.menu')} style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem', font: 'inherit', background: 'transparent', border: '1px solid var(--color-border, #ccc)', borderRadius: '0.5rem', padding: '0.3rem 0.6rem', cursor: 'pointer', color: 'inherit' }}>
            <Icon name="briefcase" size={16} />
            <span>{currentName}</span>
            <Icon name="chevron-down" size={14} />
          </button>
        }
      />
      <Dialog
        open={adding}
        onClose={() => setAdding(false)}
        title={t('switcher.dialogTitle')}
        description={t('switcher.hint')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setAdding(false)}>
              {t('switcher.cancel')}
            </Button>
            <Button loading={busy} disabled={name.trim().length < 2} onClick={() => void create()}>
              {t('switcher.create')}
            </Button>
          </>
        }
      >
        <Input label={t('switcher.name')} value={name} onChange={(event) => setName(event.target.value)} />
      </Dialog>
    </>
  );
}
