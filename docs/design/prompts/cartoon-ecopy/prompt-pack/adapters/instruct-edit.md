# 指令式编辑模型 adapter（参考图 + 自然语言）
输入：预处理后的画作图（已裁正）。模板（{preset_intent} 来自 spec.presets）：

Turn this child's drawing into a polished hand-drawn picture-book illustration of THE SAME drawing ({preset_intent}).
Keep exactly the same objects and characters, the same count, positions, sizes, proportions and poses.
Keep the child's own wobbly, naive line shapes and charming imperfections — do NOT redraw them as neat vector or geometric shapes.
Style: cute warm storybook illustration, colored-pencil and crayon strokes, soft watercolor wash fills, visible paper grain, slightly wobbly hand-drawn outlines, the child's own colors slightly brightened, warm off-white paper.
"Detail" means only stroke texture, soft shading and gentle light.
Do NOT add anything that is not in the original: no new objects, flowers, extra stars, patterns, scales, spots, new text, or background items. Keep any words the child wrote exactly as written, in their handwriting. Do not change faces or expressions. Do not complete or extend anything cut off by the photo edge.
{if decorations.enabled: You may add only: {allowed_when_enabled}.}

中文模型版：把这张孩子画作变成「同一幅画」的手绘绘本插画（{preset_intent}）。物体、数量、位置、大小、比例、姿态完全不变；保留孩子歪歪扭扭的线条和可爱的不完美，不要重画成规整矢量图形。风格：温暖可爱的绘本插画，彩铅蜡笔笔触，柔和水彩铺色，透出纸纹，略带抖动的手绘描线，沿用孩子原色并稍提亮，暖白纸底。「细节」只指笔触质感、柔和明暗和光感。原画没有的东西一律不加：不加新物体、花、多余星星、花纹、鳞片、斑点、新文字或背景；孩子写的字原样保留、保持手写感，不纠正不重打；不改表情；照片边缘被切掉的部分不要补画。
