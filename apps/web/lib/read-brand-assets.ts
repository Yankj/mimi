import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { BrandAssets } from "./brand";

/** Read the checked-in mark and mascot. Server/build only; the client receives the result as props. */
export function readBrandAssets(webRoot = process.cwd()): BrandAssets {
  const svg = readFileSync(join(webRoot, "public/brand/mark.svg"), "utf8");
  const markFill = svg.match(/fill="(#[0-9A-Fa-f]{6})"/)?.[1];
  const markPath = svg.match(/\sd="([^"]+)"/)?.[1];
  if (!markFill || !markPath) throw new Error("brand mark svg is missing fill or path");
  const png = readFileSync(join(webRoot, "public/mascot/idle.png"));
  return { markFill, markPath, mascotSrc: `data:image/png;base64,${png.toString("base64")}` };
}
