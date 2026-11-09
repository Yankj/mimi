# 扩散 img2img adapter
- 控制：线稿/边缘控制（lineart 或 canny，权重 0.8–1.0）锁住孩子的线条；可选 color/tile 控制锁配色。
- denoise：light 0.35 / storybook 0.55（来自 spec.presets；>0.6 易「重画」，禁止）。
- positive: children's crayon drawing, picture book illustration, colored pencil texture, watercolor wash, paper grain, wobbly hand-drawn lines, naive art, cute, warm, soft light
- negative: vector art, flat design, clean geometric shapes, 3d render, anime, photorealistic, extra objects, flowers, extra stars, patterns, scales, spots, added text, watermark, signature, deformed faces
- 分辨率：保持原纸面长宽比，长边 1024–1536。
