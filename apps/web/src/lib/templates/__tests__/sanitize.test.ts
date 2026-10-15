import { describe, expect, it } from 'vitest';
import { fillPlaceholders, placeholderNames, sanitizeTemplateHtml } from '../sanitize';

describe('sanitizeTemplateHtml', () => {
  it('keeps letter formatting and placeholders', () => {
    const html = '<h2>Widerspruch</h2><p>Sehr geehrte Damen und Herren, <b>{{recipient_name}}</b></p><ul><li>eins</li></ul>';
    expect(sanitizeTemplateHtml(html)).toBe(html);
  });

  it('removes scripts, styles, handlers, links and images, but keeps the text of unknown tags', () => {
    const dirty =
      '<p onclick="steal()">Hallo<script>alert(1)</script><style>p{}</style><img src=x onerror=alert(1)><a href="javascript:alert(1)">Link</a></p>';
    const clean = sanitizeTemplateHtml(dirty);
    expect(clean).toBe('<p>HalloLink</p>');
    expect(clean).not.toMatch(/script|onclick|onerror|javascript|<img|<a /i);
  });
});

describe('placeholders', () => {
  it('lists each placeholder once', () => {
    expect(placeholderNames('{{a}} und {{ b }} und {{a}}')).toEqual(['a', 'b']);
  });

  it('fills what has a sample value, leaves the rest visible, and never lets a sample inject markup', () => {
    expect(fillPlaceholders('{{name}} / {{date}}', { name: '<b>X</b> & Y' })).toBe('&lt;b&gt;X&lt;/b&gt; &amp; Y / {{date}}');
  });
});
