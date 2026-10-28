/**
 * The process editor's model: pure functions over a graph, so every edit can be tested without a browser.
 *
 * The server (WorkflowEditor) owns validity: it turns the graph into a definition, checks it and publishes it. This
 * file only edits the picture. A condition's two branches are edges labelled `true` and `false`; every other step
 * has at most one outgoing edge (`next`).
 */

export interface PaletteEntry {
  readonly type: string;
  readonly description: string;
  /** Config keys the step takes; `next`, `onTrue` and `onFalse` are drawn as edges, the rest are typed in. */
  readonly fields: readonly string[];
}

export interface GraphNode {
  readonly key: string;
  readonly type: string;
  readonly config: Readonly<Record<string, unknown>>;
  readonly x: number;
  readonly y: number;
}

export interface GraphEdge {
  readonly from: string;
  readonly to: string;
  readonly label: string;
}

export interface Graph {
  readonly entryNode: string;
  readonly nodes: readonly GraphNode[];
  readonly edges: readonly GraphEdge[];
}

export const NODE_WIDTH = 168;
export const NODE_HEIGHT = 56;
const EDGE_FIELDS = new Set(['next', 'onTrue', 'onFalse']);

export const emptyGraph: Graph = { entryNode: '', nodes: [], edges: [] };

/** The fields of a step the person types in (the rest are connections). */
export function configFields(entry: PaletteEntry | undefined): readonly string[] {
  return (entry?.fields ?? []).filter((field) => !EDGE_FIELDS.has(field));
}

/** The labels a step's outgoing edges may carry: a condition has two named branches, every other step has one. */
export function outgoingLabels(type: string): readonly string[] {
  if (type === 'TERMINATE') return [];
  return type === 'CONDITION' ? ['true', 'false'] : [''];
}

function uniqueKey(graph: Graph, base: string): string {
  const stem =
    base
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '') || 'step';
  const taken = new Set(graph.nodes.map((node) => node.key));
  if (!taken.has(stem)) return stem;
  for (let index = 2; ; index += 1) {
    if (!taken.has(`${stem}_${index}`)) return `${stem}_${index}`;
  }
}

export function addNode(graph: Graph, type: string, x?: number, y?: number): Graph {
  const key = uniqueKey(graph, type);
  const lowest = graph.nodes.reduce((max, node) => Math.max(max, node.y), -NODE_HEIGHT);
  const node: GraphNode = { key, type, config: {}, x: x ?? 40, y: y ?? lowest + NODE_HEIGHT + 48 };
  return {
    entryNode: graph.entryNode || key,
    nodes: [...graph.nodes, node],
    edges: graph.edges,
  };
}

export function moveNode(graph: Graph, key: string, x: number, y: number): Graph {
  return {
    ...graph,
    nodes: graph.nodes.map((node) =>
      node.key === key ? { ...node, x: Math.max(0, x), y: Math.max(0, y) } : node,
    ),
  };
}

export function setConfig(graph: Graph, key: string, field: string, value: string): Graph {
  return {
    ...graph,
    nodes: graph.nodes.map((node) => {
      if (node.key !== key) return node;
      const config = { ...node.config };
      if (value === '') delete config[field];
      else config[field] = value;
      return { ...node, config };
    }),
  };
}

/** Renames a step and every reference to it (edges and the entry). A key that is empty or taken is refused. */
export function renameNode(graph: Graph, from: string, to: string): Graph {
  const next = to.trim();
  if (next === '' || next === from || graph.nodes.some((node) => node.key === next)) return graph;
  return {
    entryNode: graph.entryNode === from ? next : graph.entryNode,
    nodes: graph.nodes.map((node) => (node.key === from ? { ...node, key: next } : node)),
    edges: graph.edges.map((edge) => ({
      ...edge,
      from: edge.from === from ? next : edge.from,
      to: edge.to === from ? next : edge.to,
    })),
  };
}

export function removeNode(graph: Graph, key: string): Graph {
  const nodes = graph.nodes.filter((node) => node.key !== key);
  return {
    entryNode: graph.entryNode === key ? (nodes[0]?.key ?? '') : graph.entryNode,
    nodes,
    edges: graph.edges.filter((edge) => edge.from !== key && edge.to !== key),
  };
}

export function setEntry(graph: Graph, key: string): Graph {
  return graph.nodes.some((node) => node.key === key) ? { ...graph, entryNode: key } : graph;
}

/** Points the `label` edge of `from` at `to`; an empty `to` removes it. A step may not lead to itself. */
export function connect(graph: Graph, from: string, label: string, to: string): Graph {
  const kept = graph.edges.filter((edge) => !(edge.from === from && edge.label === label));
  if (to === '' || to === from || !graph.nodes.some((node) => node.key === to))
    return { ...graph, edges: kept };
  return { ...graph, edges: [...kept, { from, to, label }] };
}

export function targetOf(graph: Graph, from: string, label: string): string {
  return graph.edges.find((edge) => edge.from === from && edge.label === label)?.to ?? '';
}

/** Keeps a graph from the server in the shape the editor works with (positions default to a column). */
export function normalizeGraph(raw: {
  entryNode?: string | null;
  nodes?: readonly {
    key: string;
    type: string;
    config?: Record<string, unknown> | null;
    x?: number | null;
    y?: number | null;
  }[];
  edges?: readonly { from: string; to: string; label?: string | null }[];
}): Graph {
  const nodes = (raw.nodes ?? []).map((node, index) => ({
    key: node.key,
    type: node.type,
    config: node.config ?? {},
    x: node.x ?? 40,
    y: node.y ?? 40 + index * (NODE_HEIGHT + 48),
  }));
  return {
    entryNode: raw.entryNode ?? nodes[0]?.key ?? '',
    nodes,
    edges: (raw.edges ?? []).map((edge) => ({
      from: edge.from,
      to: edge.to,
      label: edge.label ?? '',
    })),
  };
}

/** Steps nothing leads to and that are not the entry: the server reports them too, this lets the picture show it. */
export function unreachable(graph: Graph): readonly string[] {
  const seen = new Set<string>();
  const stack = graph.entryNode ? [graph.entryNode] : [];
  while (stack.length > 0) {
    const key = stack.pop() as string;
    if (seen.has(key)) continue;
    seen.add(key);
    for (const edge of graph.edges) if (edge.from === key) stack.push(edge.to);
  }
  return graph.nodes.map((node) => node.key).filter((key) => !seen.has(key));
}
