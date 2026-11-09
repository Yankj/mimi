# Mimi 画作卡通化 Prompt Pack v0.2.2（模型无关）

目标：妈妈拍下孩子画作 → 生成「同一幅画」的手绘绘本风电子稿（可选再生成几秒小动画）。
原则：**孩子的画是主角**。模型只负责「润色质感」，不负责「重画」。

## 结构
- `spec.json`：单一事实来源。意图、正向/负向要素、预设强度、验收条都在这里，App/后端只读它。
- `adapters/`：把 spec 渲染成各类模型能吃的提示词。换模型 = 换 adapter，不改 spec。
  - `instruct-edit.md`：指令式图像编辑模型（自然语言 + 参考图，如 GPT-image / Gemini 图像编辑 / Flux Kontext 类）。
  - `diffusion-img2img.md`：扩散 img2img（正/负提示词 + denoise 强度 + 线稿/边缘控制）。
  - `image-to-video.md`：图生视频动画。
- `acceptance.md`：自动+人工验收条，任何模型出图都按它判。
- `history/`：更早草稿 `PROMPT-v2.md`、`PROMPT-v3.md`，只保留来源，不作为事实来源。

## 版本
v0.2.2 默认（`spec.json` 的 `defaults`）：强度 `storybook`（绘本精修）；风格 `storybook_handdrawn`（绘本手绘）；装饰 `off`。轻度润色（`presets.light`）需手动切换，不是默认。
v0.2 规则：原画没有的物体一律不加；装饰做成 `decoration_levels` 档位（关/轻/闪），默认关；风格预设 `style_presets`：绘本手绘 / 更卡通。
