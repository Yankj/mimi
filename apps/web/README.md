# mimi Web

浏览器里的 mimi / 小咪。这一版是现有 catpaw edge API 的客户端，不是第二套原生宿主，也不是营销站（营销站仍在 `apps/website`，已迁出）。

登录只有 Google 与 GitHub，走 Supabase Auth 的 PKCE。没有匿名体验登录，也没有「开始使用」。未登录时只显示登录页；`/auth/callback` 只负责换票，不进入今天 / 成长 / 我们家。会话放在 `sessionStorage`，匿名标记为真的会话会被丢掉。

## 环境变量

只从环境读取，仓库里的 `.env.example` 值为空。不要写入 service role、数据库密码或模型密钥。`NEXT_PUBLIC_` 会进入浏览器，只放 Supabase 的 publishable / anon key（与 iOS 的 `apikey` 相同）。

| 变量 | 用途 |
| --- | --- |
| `NEXT_PUBLIC_SUPABASE_URL` | `https://<20位>.supabase.co` |
| `NEXT_PUBLIC_SUPABASE_ANON_KEY` | publishable key 或 role 为 anon 的旧 JWT |
| `NEXT_PUBLIC_CATPAW_EDGE_ORIGIN` | catpaw-api 的 https origin，路径为 `/` 或 `/functions/v1/<name>` |

Supabase 的重定向允许列表需要包含这个站点的 `/auth/callback`。本仓库不修改线上 Supabase 配置。

```bash
cd apps/web
npm install
npm run dev
```

## 命令

- `npm run lint`
- `npm run typecheck`
- `npm test`：请求构造的单元测试
- `npm run test:e2e`：Playwright。未登录、匿名会话被拒绝、已登录后的育儿空态。不访问线上 Supabase。
- `npm run build`

## 这一批做什么

对话走和 iOS 相同的 edge 路径与请求头（`X-Catpaw-Route`、`X-Catpaw-Model`、`X-Catpaw-Live-Text`、审批 / UI / 记忆合同）。不发送 `X-Catpaw-Device-Tools` 或 `X-Catpaw-Device-Contract`。

今天 / 成长 / 我们家只保留外壳、导航和 iOS 空态文案。`TODO：family_v1` 尚未决定，这里不保存、不同步育儿账本。「添加孩子」不会写入孩子。

## Web 难以等价

Live Activity、小组件、Siri、EventKit / 通讯录、仅限设备的语音识别、本机提醒排程、保存到照片。朗读使用浏览器 `speechSynthesis`。
