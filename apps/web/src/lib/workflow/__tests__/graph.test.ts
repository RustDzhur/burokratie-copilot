import { describe, expect, it } from 'vitest';
import {
  addNode,
  connect,
  emptyGraph,
  moveNode,
  normalizeGraph,
  outgoingLabels,
  removeNode,
  renameNode,
  setConfig,
  targetOf,
  unreachable,
} from '../graph';

describe('process graph', () => {
  it('the first step becomes the entry and keys are made unique', () => {
    let graph = addNode(emptyGraph, 'ACTION');
    graph = addNode(graph, 'ACTION');
    graph = addNode(graph, 'CONDITION');
    expect(graph.entryNode).toBe('action');
    expect(graph.nodes.map((node) => node.key)).toEqual(['action', 'action_2', 'condition']);
    expect(graph.nodes[1]?.y).toBeGreaterThan(graph.nodes[0]?.y ?? 0);
  });

  it('connecting replaces the edge with the same label and refuses a self-loop or an unknown target', () => {
    let graph = addNode(addNode(addNode(emptyGraph, 'CONDITION'), 'ACTION'), 'TERMINATE');
    graph = connect(graph, 'condition', 'true', 'action');
    graph = connect(graph, 'condition', 'false', 'terminate');
    graph = connect(graph, 'condition', 'true', 'terminate');
    expect(targetOf(graph, 'condition', 'true')).toBe('terminate');
    expect(targetOf(graph, 'condition', 'false')).toBe('terminate');
    expect(connect(graph, 'action', '', 'action').edges).toEqual(graph.edges);
    expect(connect(graph, 'action', '', 'nowhere').edges).toEqual(graph.edges);
    expect(
      connect(graph, 'condition', 'true', '').edges.some((edge) => edge.label === 'true'),
    ).toBe(false);
  });

  it('renaming follows the edges and the entry; a taken or empty name is refused', () => {
    let graph = connect(
      addNode(addNode(emptyGraph, 'ACTION'), 'TERMINATE'),
      'action',
      '',
      'terminate',
    );
    graph = renameNode(graph, 'action', 'pruefen');
    expect(graph.entryNode).toBe('pruefen');
    expect(graph.edges).toEqual([{ from: 'pruefen', to: 'terminate', label: '' }]);
    expect(renameNode(graph, 'pruefen', 'terminate')).toBe(graph);
    expect(renameNode(graph, 'pruefen', '  ')).toBe(graph);
  });

  it('removing a step removes its edges and moves the entry', () => {
    const graph = connect(
      addNode(addNode(emptyGraph, 'ACTION'), 'TERMINATE'),
      'action',
      '',
      'terminate',
    );
    const reduced = removeNode(graph, 'action');
    expect(reduced.entryNode).toBe('terminate');
    expect(reduced.edges).toEqual([]);
  });

  it('config values are set and cleared; positions never go negative', () => {
    let graph = addNode(emptyGraph, 'DELAY');
    graph = setConfig(graph, 'delay', 'seconds', '60');
    expect(graph.nodes[0]?.config).toEqual({ seconds: '60' });
    graph = setConfig(graph, 'delay', 'seconds', '');
    expect(graph.nodes[0]?.config).toEqual({});
    expect(moveNode(graph, 'delay', -5, -9).nodes[0]).toMatchObject({ x: 0, y: 0 });
  });

  it('finds steps nothing leads to', () => {
    const graph = connect(
      addNode(addNode(addNode(emptyGraph, 'ACTION'), 'TERMINATE'), 'ACTION'),
      'action',
      '',
      'terminate',
    );
    expect(unreachable(graph)).toEqual(['action_2']);
  });

  it('a condition has two named branches, a terminator none', () => {
    expect(outgoingLabels('CONDITION')).toEqual(['true', 'false']);
    expect(outgoingLabels('TERMINATE')).toEqual([]);
    expect(outgoingLabels('ACTION')).toEqual(['']);
  });

  it('normalizes what the server sends', () => {
    const graph = normalizeGraph({
      entryNode: 'a',
      nodes: [{ key: 'a', type: 'ACTION' }],
      edges: [{ from: 'a', to: 'b', label: null }],
    });
    expect(graph.nodes[0]).toMatchObject({ x: 40, y: 40, config: {} });
    expect(graph.edges[0]?.label).toBe('');
  });
});
