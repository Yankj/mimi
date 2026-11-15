/** Brand bytes for the first screen. The strings are the existing assets, not new artwork. */
export type BrandAssets = {
  /** `d` from docs/brand/mark.svg (copied into public/brand/mark.svg). */
  markPath: string;
  /** `fill` from that same file. */
  markFill: string;
  /** `public/mascot/idle.png` as a data URL, so the first HTML paint includes the pixels. */
  mascotSrc: string;
};
