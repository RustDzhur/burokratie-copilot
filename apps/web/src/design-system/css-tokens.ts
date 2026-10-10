/**
 * Minimal CSS custom-property reader for tests and design tooling.
 *
 * It intentionally supports only the small subset of CSS used by tokens.css:
 * flat rule blocks with `--custom-property: value;` declarations. This keeps the
 * contrast test honest — it asserts on the shipped stylesheet, not on a copy of
 * the palette that could silently drift.
 */

export type TokenMap = Record<string, string>;

export interface TokenBlock {
  /** Selector of the rule, e.g. `:root` or `[data-theme='dark']`. */
  readonly selector: string;
  readonly tokens: TokenMap;
}

const COMMENT_PATTERN = /\/\*[\s\S]*?\*\//g;

/** Strips CSS comments so declarations inside comments are ignored. */
export function stripComments(css: string): string {
  return css.replace(COMMENT_PATTERN, '');
}

/** Parses every rule block of a stylesheet into selector + custom properties. */
export function parseTokenBlocks(css: string): TokenBlock[] {
  const source = stripComments(css);
  const blocks: TokenBlock[] = [];
  const blockPattern = /([^{}]+)\{([^{}]*)\}/g;
  let match = blockPattern.exec(source);
  while (match !== null) {
    const selector = (match[1] ?? '').trim().replace(/\s+/g, ' ');
    const body = match[2] ?? '';
    const tokens: TokenMap = {};
    const declarationPattern = /(--[a-z0-9-]+)\s*:\s*([^;]+);/gi;
    let declaration = declarationPattern.exec(body);
    while (declaration !== null) {
      const name = declaration[1];
      const value = declaration[2];
      if (name && value) tokens[name] = value.trim();
      declaration = declarationPattern.exec(body);
    }
    if (Object.keys(tokens).length > 0) blocks.push({ selector, tokens });
    match = blockPattern.exec(source);
  }
  return blocks;
}

/** Merges token maps left to right; later maps win (CSS cascade order). */
export function mergeTokens(...maps: readonly TokenMap[]): TokenMap {
  return Object.assign({}, ...maps);
}

/** Returns a theme map by exact selector, e.g. `getBlock(blocks, ':root')`. */
export function getBlock(blocks: readonly TokenBlock[], selector: string): TokenMap {
  const found = blocks.find((block) => block.selector === selector);
  if (!found) {
    throw new Error(`Token block not found for selector "${selector}"`);
  }
  return found.tokens;
}

/**
 * Resolves a token, following `var(--other)` indirections (with optional
 * fallbacks) until a literal value is found. Returns `null` for unknown tokens.
 */
export function resolveToken(tokens: TokenMap, name: string, depth = 0): string | null {
  if (depth > 10) return null;
  const raw = tokens[name];
  if (raw === undefined) return null;
  const varMatch = /^var\(\s*(--[a-z0-9-]+)\s*(?:,\s*([^)]+))?\)$/i.exec(raw.trim());
  if (!varMatch) return raw;
  const referenced = varMatch[1];
  if (!referenced) return raw;
  const resolved = resolveToken(tokens, referenced, depth + 1);
  if (resolved !== null) return resolved;
  return varMatch[2]?.trim() ?? null;
}
