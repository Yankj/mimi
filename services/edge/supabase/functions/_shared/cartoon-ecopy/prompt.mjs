import {ApiError,check} from '../core/facade-r2/src/common.mjs';

// Renders the instruct-edit adapter from prompt-pack/adapters/instruct-edit.md.
// The only inputs are spec enums. Child name, age, location and family names are never interpolated.
export function renderInstructEditPrompt(spec, choice) {
  const preset = spec?.presets?.[choice?.preset];
  const style = spec?.style_presets?.[choice?.style_preset];
  const decoration = spec?.decoration_levels?.[choice?.decoration_level];
  check(preset && style && decoration && Array.isArray(style.keywords) && Array.isArray(decoration.add), 422, 'schema_invalid');
  const keywords = style.keywords.join(', ');
  const english = [
    `Turn this child's drawing into a polished hand-drawn picture-book illustration of THE SAME drawing (${preset.intent}).`,
    'Keep exactly the same objects and characters, the same count, positions, sizes, proportions and poses.',
    "Keep the child's own wobbly, naive line shapes and charming imperfections — do NOT redraw them as neat vector or geometric shapes.",
    `Style: ${keywords}.`,
    '"Detail" means only stroke texture, soft shading and gentle light.',
    'Do NOT add anything that is not in the original: no new objects, flowers, extra stars, patterns, scales, spots, new text, or background items. Keep any words the child wrote exactly as written, in their handwriting. Do not change faces or expressions. Do not complete or extend anything cut off by the photo edge.',
  ];
  const chinese = [
    `把这张孩子画作变成「同一幅画」的手绘绘本插画（${preset.intent}）。物体、数量、位置、大小、比例、姿态完全不变；保留孩子歪歪扭扭的线条和可爱的不完美，不要重画成规整矢量图形。`,
    `风格：${keywords}。沿用孩子原色并稍提亮。「细节」只指笔触质感、柔和明暗和光感。`,
    '原画没有的东西一律不加：不加新物体、花、多余星星、花纹、鳞片、斑点、新文字或背景；孩子写的字原样保留、保持手写感，不纠正不重打；不改表情；照片边缘被切掉的部分不要补画。',
  ];
  if (decoration.add.length) {
    const allowed = decoration.add.join(', ');
    english.push(`You may add only: ${allowed}.`);
    chinese.push(`只允许添加：${decoration.add.join('、')}。`);
  } else {
    english.push('Do not add cheek blush, eye highlights, sparkles, or glow.');
    chinese.push('不要加腮红、眼睛高光、闪光或发光。');
  }
  return [...english, ...chinese].join('\n');
}

const ALLOWED = new Set(['image_base64', 'mime_type', 'preset', 'style_preset', 'decoration_level']);
const FORBIDDEN = new Set([
  'child_name', 'childname', 'name', 'age', 'location', 'family_name', 'familyname', 'family_names',
  'display_name', 'nickname', 'school', 'address', 'birthday', 'birth_year', 'child', 'children',
]);

export function assertCartoonRequest(body) {
  check(body && typeof body === 'object' && !Array.isArray(body) && Object.getPrototypeOf(body) === Object.prototype, 422, 'schema_invalid');
  for (const key of Object.keys(body)) {
    const norm = key.toLowerCase().replace(/[-\s]/g, '_');
    if (FORBIDDEN.has(norm)) throw new ApiError(422, 'privacy_field');
    check(ALLOWED.has(key), 422, 'schema_invalid');
  }
  check(typeof body.image_base64 === 'string' && typeof body.mime_type === 'string', 422, 'schema_invalid');
}

export function cartoonChoices(spec, body) {
  const preset = body.preset ?? spec.defaults?.preset;
  const style_preset = body.style_preset ?? spec.defaults?.style_preset;
  const decoration_level = body.decoration_level ?? spec.defaults?.decoration_level;
  check(spec.presets?.[preset] && spec.style_presets?.[style_preset] && spec.decoration_levels?.[decoration_level], 422, 'schema_invalid');
  return {preset, style_preset, decoration_level};
}
