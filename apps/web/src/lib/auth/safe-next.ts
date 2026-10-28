/**
 * Where to go after signing in. Only a path inside this application is accepted: a `next` that names another
 * site (`//evil.example`, `https://…`, `/\evil`) would turn the sign-in into an open redirect, and the sign-in
 * and registration screens are not a destination.
 */
export function safeNextPath(next: string | null | undefined): string | null {
  if (!next || !next.startsWith('/')) return null;
  if (next.startsWith('//') || next.startsWith('/\\')) return null;
  if (next.startsWith('/login') || next.startsWith('/register')) return null;
  // Control characters are how a scheme is smuggled past a prefix check.
  if ([...next].some((char) => char.charCodeAt(0) < 0x20 || char.charCodeAt(0) === 0x7f)) return null;
  return next;
}
