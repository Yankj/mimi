/** Composer drag-and-drop helpers. Files still go through the existing image upload path. */

const IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/jpg"]);

export type DroppedFile = { type: string; name?: string };

export function hasFileDrag(types: { contains?(type: string): boolean; length: number; [index: number]: string } | ArrayLike<string> | null | undefined) {
  if (!types) return false;
  if (typeof (types as { contains?: unknown }).contains === "function") return (types as { contains(type: string): boolean }).contains("Files");
  return Array.from(types as ArrayLike<string>).includes("Files");
}

export function isImageFile(file: DroppedFile) {
  if (IMAGE_TYPES.has(file.type)) return true;
  const name = (file.name ?? "").toLowerCase();
  return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
}

export function firstDroppedImage<T extends DroppedFile>(files: ArrayLike<T> | null | undefined): { file: T; extraImages: number } | null {
  if (!files || files.length === 0) return null;
  const images = Array.from(files as ArrayLike<T> as T[]).filter(isImageFile);
  if (images.length === 0) return null;
  return { file: images[0], extraImages: images.length - 1 };
}
