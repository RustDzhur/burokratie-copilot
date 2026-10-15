/* The mascot of the brand: a friendly robot pilot with the documents. Decorative where the name stands next to it. */
export function BrandMark({ size = 36 }: { readonly size?: number }) {
  return (
    // A plain image on purpose: the file is small, static and the same for everybody.
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src="/brand/mascot.webp"
      alt=""
      width={size}
      height={size}
      style={{ width: size, height: size, display: 'block', flexShrink: 0 }}
    />
  );
}
