'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
} from 'react';
import { PageHeader, PageSection } from '@/components/app/resource-view';
import { Card, CardBody, CardHeader, Select } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { InlineAlert, Spinner } from '@/components/ui/feedback';
import { Input } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import {
  NODE_HEIGHT,
  NODE_WIDTH,
  addNode,
  configFields,
  connect,
  emptyGraph,
  moveNode,
  outgoingLabels,
  removeNode,
  renameNode,
  setConfig,
  setEntry,
  targetOf,
  unreachable,
  type Graph,
  type PaletteEntry,
} from '@/lib/workflow/graph';
import styles from './editor.module.css';

const STEP = 16;

/**
 * Process editor (spec §16, F-004). The picture is edited here; whether it is a valid process is decided by the
 * server (`Prüfen` calls the same validation that `Veröffentlichen` runs). Everything that can be done with the
 * mouse can be done with the keyboard: a step is a focusable control, arrow keys move it, and connections are chosen
 * from a list instead of dragged.
 */
export default function AutomationEditorPage() {
  const t = useTranslations('automationEditor');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const { id } = useParams<{ id: string }>();

  const [palette, setPalette] = useState<readonly PaletteEntry[]>([]);
  const [graph, setGraph] = useState<Graph>(emptyGraph);
  const [selected, setSelected] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [problems, setProblems] = useState<readonly string[] | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const drag = useRef<{ key: string; dx: number; dy: number } | null>(null);
  const svgRef = useRef<SVGSVGElement | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      api.workflows.palette(controller.signal),
      api.workflows.graph(id, controller.signal),
    ])
      .then(([entries, saved]) => {
        setPalette(entries);
        setGraph(saved ?? emptyGraph);
        setSelected(saved?.entryNode ?? null);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setLoadError(tErrors(toApiError(error).kind));
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [id, tErrors]);

  const paletteByType = useMemo(
    () => new Map(palette.map((entry) => [entry.type, entry])),
    [palette],
  );
  const hanging = useMemo(() => new Set(unreachable(graph)), [graph]);
  const node = graph.nodes.find((candidate) => candidate.key === selected) ?? null;
  const entry = node ? paletteByType.get(node.type) : undefined;

  const edit = useCallback((next: Graph) => {
    setGraph(next);
    setProblems(null);
  }, []);

  const toCanvas = (event: PointerEvent): { x: number; y: number } => {
    const box = svgRef.current?.getBoundingClientRect();
    return { x: event.clientX - (box?.left ?? 0), y: event.clientY - (box?.top ?? 0) };
  };

  const onPointerDown = (event: PointerEvent<SVGGElement>, key: string): void => {
    const target = graph.nodes.find((candidate) => candidate.key === key);
    if (!target) return;
    const point = toCanvas(event);
    drag.current = { key, dx: point.x - target.x, dy: point.y - target.y };
    event.currentTarget.setPointerCapture(event.pointerId);
    setSelected(key);
  };
  const onPointerMove = (event: PointerEvent<SVGGElement>): void => {
    if (!drag.current) return;
    const point = toCanvas(event);
    edit(
      moveNode(
        graph,
        drag.current.key,
        Math.round(point.x - drag.current.dx),
        Math.round(point.y - drag.current.dy),
      ),
    );
  };
  const onKeyDown = (event: KeyboardEvent<SVGGElement>, key: string): void => {
    const target = graph.nodes.find((candidate) => candidate.key === key);
    if (!target) return;
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [-STEP, 0],
      ArrowRight: [STEP, 0],
      ArrowUp: [0, -STEP],
      ArrowDown: [0, STEP],
    };
    const move = moves[event.key];
    if (move) {
      event.preventDefault();
      edit(moveNode(graph, key, target.x + move[0], target.y + move[1]));
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      setSelected(key);
    } else if (event.key === 'Delete') {
      event.preventDefault();
      edit(removeNode(graph, key));
      setSelected(null);
    }
  };

  const check = async (): Promise<void> => {
    setBusy(true);
    try {
      const found = await api.workflows.check(id, graph);
      setProblems(found.map((problem) => problem.message));
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const publish = async (): Promise<void> => {
    setBusy(true);
    try {
      const found = await api.workflows.check(id, graph);
      if (found.length > 0) {
        setProblems(found.map((problem) => problem.message));
        return;
      }
      const published = await api.workflows.publish(id, graph, note.trim() || undefined);
      setProblems([]);
      toast.success(t('published', { version: published.version }));
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const height = Math.max(420, ...graph.nodes.map((candidate) => candidate.y + NODE_HEIGHT + 40));
  const width = Math.max(720, ...graph.nodes.map((candidate) => candidate.x + NODE_WIDTH + 40));
  const center = (key: string): { x: number; y: number } | null => {
    const found = graph.nodes.find((candidate) => candidate.key === key);
    return found ? { x: found.x + NODE_WIDTH / 2, y: found.y + NODE_HEIGHT / 2 } : null;
  };

  if (loading) return <Spinner />;

  return (
    <>
      <PageHeader
        title={t('title')}
        subtitle={undefined}
        actions={
          <Link href="/automations" className={buttonClass({ variant: 'ghost' })}>
            {t('back')}
          </Link>
        }
      />
      {loadError ? <InlineAlert tone="danger" title={loadError} /> : null}

      <div className={styles.layout}>
        <PageSection title={t('canvas')} description={t('canvasHelp')}>
          {graph.nodes.length === 0 ? <p>{t('empty')}</p> : null}
          <div className={styles.canvasWrap}>
            <svg
              ref={svgRef}
              className={styles.canvas}
              width={width}
              height={height}
              role="group"
              aria-label={t('canvas')}
              onPointerUp={() => {
                drag.current = null;
              }}
            >
              <defs>
                <marker
                  id="arrow"
                  viewBox="0 0 10 10"
                  refX="9"
                  refY="5"
                  markerWidth="7"
                  markerHeight="7"
                  orient="auto-start-reverse"
                >
                  <path d="M0 0 L10 5 L0 10 z" fill="currentColor" />
                </marker>
              </defs>
              {graph.edges.map((edge) => {
                const from = center(edge.from);
                const to = center(edge.to);
                if (!from || !to) return null;
                return (
                  <g
                    key={`${edge.from}->${edge.to}:${edge.label}`}
                    color="var(--color-text-muted, #9aa7bd)"
                  >
                    <line
                      className={styles.edge}
                      x1={from.x}
                      y1={from.y}
                      x2={to.x}
                      y2={to.y}
                      markerEnd="url(#arrow)"
                    />
                    {edge.label ? (
                      <text
                        className={styles.edgeLabel}
                        x={(from.x + to.x) / 2 + 4}
                        y={(from.y + to.y) / 2 - 4}
                      >
                        {edge.label}
                      </text>
                    ) : null}
                  </g>
                );
              })}
              {graph.nodes.map((candidate) => (
                <g
                  key={candidate.key}
                  className={[
                    styles.node,
                    candidate.key === selected ? styles.selected : '',
                    candidate.key === graph.entryNode ? styles.entry : '',
                    hanging.has(candidate.key) ? styles.unreachable : '',
                  ].join(' ')}
                  transform={`translate(${candidate.x} ${candidate.y})`}
                  tabIndex={0}
                  role="button"
                  aria-pressed={candidate.key === selected}
                  aria-label={`${candidate.key} (${candidate.type})${candidate.key === graph.entryNode ? `, ${t('entry')}` : ''}${hanging.has(candidate.key) ? `, ${t('unreachable')}` : ''}`}
                  onPointerDown={(event) => onPointerDown(event, candidate.key)}
                  onPointerMove={onPointerMove}
                  onKeyDown={(event) => onKeyDown(event, candidate.key)}
                  onFocus={() => setSelected(candidate.key)}
                >
                  <rect width={NODE_WIDTH} height={NODE_HEIGHT} rx={8} />
                  <text x={12} y={24}>
                    {candidate.key}
                  </text>
                  <text x={12} y={42} opacity={0.7}>
                    {candidate.type}
                  </text>
                </g>
              ))}
            </svg>
          </div>
        </PageSection>

        <div className={styles.panel}>
          <Card>
            <CardHeader title={t('palette')} headingLevel={2} />
            <CardBody>
              <div className={styles.palette}>
                {palette.map((option) => (
                  <Button
                    key={option.type}
                    variant="secondary"
                    size="sm"
                    title={option.description}
                    onClick={() => {
                      const next = addNode(graph, option.type);
                      edit(next);
                      setSelected(next.nodes[next.nodes.length - 1]?.key ?? null);
                    }}
                  >
                    {option.type}
                  </Button>
                ))}
              </div>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title={t('selected')} headingLevel={2} />
            <CardBody>
              {!node ? (
                <p>{t('none')}</p>
              ) : (
                <div className={styles.panel}>
                  <Input
                    label={t('key')}
                    value={node.key}
                    onChange={(event) => {
                      const next = renameNode(graph, node.key, event.target.value);
                      edit(next);
                      if (next !== graph) setSelected(event.target.value.trim());
                    }}
                  />
                  <p>
                    {t('type')}: <strong>{node.type}</strong>
                    {entry ? ` – ${entry.description}` : ''}
                  </p>
                  {configFields(entry).map((field) => (
                    <Input
                      key={field}
                      label={field}
                      value={String(node.config[field] ?? '')}
                      onChange={(event) =>
                        edit(setConfig(graph, node.key, field, event.target.value))
                      }
                    />
                  ))}
                  {outgoingLabels(node.type).map((label) => (
                    <Select
                      key={label || 'next'}
                      label={
                        label === 'true'
                          ? t('trueTo')
                          : label === 'false'
                            ? t('falseTo')
                            : t('leadsTo')
                      }
                      value={targetOf(graph, node.key, label)}
                      placeholder={t('noTarget')}
                      options={graph.nodes
                        .filter((candidate) => candidate.key !== node.key)
                        .map((candidate) => ({ value: candidate.key, label: candidate.key }))}
                      onChange={(event) =>
                        edit(connect(graph, node.key, label, event.target.value))
                      }
                    />
                  ))}
                  <div className={styles.palette}>
                    {node.key !== graph.entryNode ? (
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => edit(setEntry(graph, node.key))}
                      >
                        {t('makeEntry')}
                      </Button>
                    ) : null}
                    <Button
                      variant="danger"
                      size="sm"
                      onClick={() => {
                        edit(removeNode(graph, node.key));
                        setSelected(null);
                      }}
                    >
                      {t('remove')}
                    </Button>
                  </div>
                </div>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardBody>
              <div className={styles.panel}>
                {problems && problems.length === 0 ? (
                  <InlineAlert tone="success" title={t('valid')} />
                ) : null}
                {problems && problems.length > 0 ? (
                  <InlineAlert tone="danger" title={t('problems')}>
                    <ul className={styles.problems}>
                      {problems.map((message) => (
                        <li key={message}>{message}</li>
                      ))}
                    </ul>
                  </InlineAlert>
                ) : null}
                <Input
                  label={t('note')}
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                />
                <div className={styles.palette}>
                  <Button variant="secondary" onClick={() => void check()} loading={busy}>
                    {t('check')}
                  </Button>
                  <Button
                    variant="primary"
                    onClick={() => void publish()}
                    loading={busy}
                    disabled={graph.nodes.length === 0}
                  >
                    {t('publish')}
                  </Button>
                </div>
              </div>
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  );
}
