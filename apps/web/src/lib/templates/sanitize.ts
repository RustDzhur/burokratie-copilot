/**
 * The HTML a template may contain. The visual editor produces it with the browser's own editing commands, and a pasted
 * page can bring anything: scripts, styles, event handlers, images from elsewhere. What is not on this list is removed
 * (the text inside is kept), so a template can only ever hold simple letter formatting and its placeholders.
 */
const ALLOWED = new Set(['P', 'BR', 'B', 'STRONG', 'I', 'EM', 'U', 'H1', 'H2', 'H3', 'UL', 'OL', 'LI', 'DIV', 'SPAN']);
const DROP_WITH_CONTENT = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'TEMPLATE', 'NOSCRIPT']);

function clean(node: Node, out: Document): Node[] {
  if (node.nodeType === Node.TEXT_NODE) return [out.createTextNode(node.textContent ?? '')];
  if (node.nodeType !== Node.ELEMENT_NODE) return [];
  const element = node as Element;
  if (DROP_WITH_CONTENT.has(element.tagName)) return [];
  const children = Array.from(element.childNodes).flatMap((child) => clean(child, out));
  if (!ALLOWED.has(element.tagName)) return children;
  const copy = out.createElement(element.tagName.toLowerCase());
  children.forEach((child) => copy.appendChild(child));
  return [copy];
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function sanitizeTemplateHtml(html: string): string {
  if (html.length === 0) return '';
  // On the server (page rendering) there is no DOM parser. Nothing is let through there: the text is escaped, and the browser
  // sanitises properly as soon as it takes over.
  if (typeof DOMParser === 'undefined') return escapeHtml(html);
  const parsed = new DOMParser().parseFromString(html, 'text/html');
  const holder = parsed.createElement('div');
  Array.from(parsed.body.childNodes).flatMap((node) => clean(node, parsed)).forEach((node) => holder.appendChild(node));
  return holder.innerHTML.trim();
}

/** The placeholder names written in the text, e.g. {{recipient_name}}. */
export function placeholderNames(html: string): string[] {
  const found = new Set<string>();
  for (const match of html.matchAll(/\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*}}/g)) {
    if (match[1]) found.add(match[1]);
  }
  return [...found];
}

/** The text with the placeholders filled by sample values, for the preview; an unfilled one stays visible as it is. */
export function fillPlaceholders(html: string, values: Readonly<Record<string, string>>): string {
  return html.replace(/\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*}}/g, (whole, name: string) =>
    values[name] ? values[name].replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') : whole,
  );
}
