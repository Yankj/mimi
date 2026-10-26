# 仓库体积审计（2026-10-25）

目标：降低 `Yankj/mimi` 克隆与工作树体积。本轮只改**当前树**与 `.gitignore`，**不重写历史**。

测量环境：box 全量 clone（`git clone https://github.com/Yankj/mimi.git`），时区 Asia/Shanghai。

## 1. 实测基线（改前，main `ab69aee`）

| 指标 | 数值 |
| --- | --- |
| GitHub API `size` | 85197 KB（约 83.2 MiB） |
| `git count-objects` `size-pack` | **83.85 MiB** |
| 工作树（不含 `.git`，`du -sb`） | **53 700 510 B（约 51.2 MiB）** |
| 检出总目录（含 `.git`） | 约 142 MiB |
| LFS | 未使用（无 `git lfs`，`.gitattributes` 仅 `-text` / whitespace） |
| 当前树最大文件 | `docs/acceptance/family-companion/memory-cases-v1.json`（3 950 467 B） |

### 工作树目录（改前）

| 路径 | 约 |
| --- | --- |
| `docs/` | 35 MiB |
| `apps/` | 15 MiB |
| `services/` | 5.1 MiB |
| 其余 | <1 MiB |

### 历史中已删但仍占 pack 的大 blob（≥500 KB 且不在当时 HEAD）

合计约 **45.1 MiB**（未去重压缩前的 blob 字节；pack 会再压缩）：

| 约大小 | 路径（历史） |
| --- | ---: |
| 21.4 MiB | `apps/website/dist/assets/launch-final-v2.mp4` |
| 6.9 MiB | `docs/launch/assets/video/animatic-v2.mp4` |
| 4.1 MiB | `apps/website/dist/assets/catpaw-product-deck.pdf` |
| 2.1 MiB | `docs/launch/assets/video/animatic-v2-20s-9x16.mp4` |
| 2.0 MiB | 旧版 `docs/refs/brand/02-brand-sheet.png` |
| 1.9 MiB | `docs/launch/assets/user/mascot-sheet.png` |
| 1.4 MiB | `docs/launch/assets/user/brand-board.png` |
| 1.3 MiB | `docs/launch/assets/deck-v3/demo-deck-v3.html` |
| 其余 | 旧 AppIcon / launch 截图等 |

结论：**克隆慢的主因是 pack 里的历史大媒体**（尤其已删除的 mp4/pdf），不是当前 Swift 源码。

## 2. 本轮已做（当前树，安全）

### 2.1 图片压缩（54 个文件，合计约 **-15.6 MiB**）

- **AppIcon / `docs/brand` / Mascot PNG**：仅 `optipng -o2` **无损**。
- **其余 `docs/` 大图**（refs、design、assets、acceptance evidence）：`optipng` + `pngquant --quality=80-95`（仅当更小才替换）；大 JPG 在体积下降 ≥5% 时用 JPEG quality 85 重压。
- **未改**冻结语料 `docs/acceptance/family-companion/memory-cases-v1.json`（文档记载 SHA256 `23f0ffc74c71ab7947440e41da08ae405b3b0d52f3375f2ce0611a7dbfb21c74`，实测一致）。
- **未删**仍被引用的 `docs/acceptance`、品牌源 `docs/brand`；内部标识符未改。

### 2.2 `.gitignore` 加固

新增：

- `docs/launch/`（历史大媒体目录，防止再提交）
- `*.mp4`（当前树无需要的 mp4；吉祥物动效为 `.mov`）
- `**/.swiftpm/xcode/`、`**/SourcePackages/`

原有 `*.ipa` / `*.zip` / `DerivedData` / `apps/website/dist/` 等保持不变。

### 2.3 改后实测（本分支工作树）

| 指标 | 数值 |
| --- | --- |
| 工作树（不含 `.git`，`du -sb`） | **38 058 175 B（约 36.3 MiB）** |
| 相对改前 | **-15 642 335 B（约 -29.1%）** |
| `size-pack`（本机 clone，历史未改） | 仍约 **83.85 MiB**（预期：不重写历史则 pack 几乎不变） |

合并后，新 clone 的**工作树**会变小；**首次全量 clone 的 pack**仍接近现状，直到做第 3 节的历史清理。

## 3. 未执行、需你批准后才能做

### 3.1 `git filter-repo` 清历史大文件（收益最大）

建议从 pack 中剥离并重写可达历史（**会改写 SHA，需 force-push 与协作者重置**）：

```text
apps/website/dist/assets/launch-final-v2.mp4
docs/launch/assets/video/animatic-v2.mp4
apps/website/dist/assets/catpaw-product-deck.pdf
docs/launch/assets/video/animatic-v2-20s-9x16.mp4
docs/launch/assets/user/mascot-sheet.png
docs/launch/assets/user/brand-board.png
docs/launch/assets/deck-v3/demo-deck-v3.html
# 以及其它仅存在于历史的 docs/launch/** 大图
```

粗估：仅上述 mp4+pdf 即可去掉约 **34+ MiB** 原始 blob；pack 有望降到约 **50 MiB 量级**（取决于 zlib 与其余对象）。  
**本 PR 不执行 filter-repo、不 force-push。**

### 3.2 冻结 JSON 紧凑化（可选）

`memory-cases-v1.json` 美化缩进约 3.95 MiB → 紧凑约 2.38 MiB（约 -40%）。  
需新版本号、更新 evaluation 文档中的 SHA256，并确认 M3 gate 接受新字节。未做。

`product-contract-crosswalk-v1.json` 约 397 KB → 紧凑约 302 KB；内含 `raw_crosswalk_sha256` 字段，改前需对账。未做。

### 3.3 其它未动

- 不删除 `docs/refs` / design 原型图（仍有文档引用；已压缩）。
- 不改 `.github/workflows`。
- 不推 `main`、不改写已推送历史。

## 4. 取舍说明

| 项 | 决定 | 原因 |
| --- | --- | --- |
| AppIcon 无损压 | 做 | 体积明显下降且无画质损失；1024 图标无透明通道符合商店习惯 |
| docs 设计/验收截图有损 | 做 | 文档示意用途，quality 80–95；路径与引用不变 |
| `docs/brand` 有损 | 不做 | 品牌母版 |
| 删 acceptance / brand | 不做 | 约束要求保留 |
| 历史 filter-repo | 仅文档 | 需显式批准与协调 force-push |

## 5. 复测命令

```bash
git count-objects -vH
du -sb --exclude=.git .
git rev-list --objects --all | git cat-file --batch-check='%(objecttype) %(objectname) %(objectsize) %(rest)' \
  | awk '/^blob/ {print $3, $2, $4}' | sort -nr | head -30
```

---

审计执行：box 本地任务（CloudAgent 区域不可用时的等价替代）。作者约定：`Yankj <hello@heydusk.com>`。
