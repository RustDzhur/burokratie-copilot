'use client';

import Link from 'next/link';
import { useTranslations } from '@/i18n/provider';

export type GraphKind = 'document' | 'contract' | 'invoice' | 'case';

export interface GraphNode {
  readonly id: string;
  readonly kind: GraphKind;
  readonly label: string;
  readonly href: string;
  /** A suggestion nobody confirmed yet is drawn dashed. */
  readonly suggested?: boolean;
}

const COLOR: Record<GraphKind, string> = { document: '#2563eb', contract: '#7c3aed', invoice: '#059669', case: '#d97706' };
const SIZE = 360;
const CENTER = SIZE / 2;
const RADIUS = 130;
const MAX_NODES = 24;

/** Where the n-th of `count` nodes sits on the ring around the centre. */
export function positionOf(index: number, count: number): { readonly x: number; readonly y: number } {
  const angle = (2 * Math.PI * index) / Math.max(1, count) - Math.PI / 2;
  return { x: Math.round(CENTER + RADIUS * Math.cos(angle)), y: Math.round(CENTER + RADIUS * Math.sin(angle)) };
}

/**
 * What is connected to a contact, drawn as a ring around it. The drawing is an addition: the same items are listed below it
 * with links, so nothing depends on seeing colours or following lines, and a screen reader gets the list.
 */
export function RelationshipGraph({ centre, nodes }: { readonly centre: string; readonly nodes: readonly GraphNode[] }) {
  const t = useTranslations('entityMemory');
  const shown = nodes.slice(0, MAX_NODES);
  const hidden = nodes.length - shown.length;
  if (nodes.length === 0) return <p>{t('graph.empty')}</p>;
  return (
    <figure data-testid="relationship-graph" style={{ margin: 0 }}>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label={t('graph.label', { count: nodes.length })} style={{ width: '100%', maxWidth: '28rem' }}>
        {shown.map((node, index) => {
          const { x, y } = positionOf(index, shown.length);
          return (
            <line
              key={`edge-${node.id}`}
              x1={CENTER}
              y1={CENTER}
              x2={x}
              y2={y}
              stroke="currentColor"
              strokeOpacity={0.5}
              strokeDasharray={node.suggested ? '4 3' : undefined}
            />
          );
        })}
        <circle cx={CENTER} cy={CENTER} r={30} fill="currentColor" fillOpacity={0.12} stroke="currentColor" />
        <text x={CENTER} y={CENTER + 4} textAnchor="middle" fontSize="11">
          {centre.length > 14 ? `${centre.slice(0, 13)}…` : centre}
        </text>
        {shown.map((node, index) => {
          const { x, y } = positionOf(index, shown.length);
          return <circle key={`node-${node.id}`} cx={x} cy={y} r={9} fill={COLOR[node.kind]} fillOpacity={node.suggested ? 0.35 : 1} stroke={COLOR[node.kind]} />;
        })}
      </svg>
      <figcaption>
        <ul>
          {shown.map((node) => (
            <li key={node.id} data-testid="graph-item">
              <span aria-hidden style={{ color: COLOR[node.kind] }}>
                ●{' '}
              </span>
              {t(`graph.kind.${node.kind}` as 'graph.kind.document')}: <Link href={node.href}>{node.label}</Link>
              {node.suggested ? <small> · {t('status.SUGGESTED')}</small> : null}
            </li>
          ))}
        </ul>
        {hidden > 0 ? <p>{t('graph.more', { count: hidden })}</p> : null}
      </figcaption>
    </figure>
  );
}
