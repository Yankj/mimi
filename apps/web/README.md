# mimi Web

浏览器里的 mimi / 小咪。这一版是现有 catpaw edge API 的客户端，不是第二套原生宿主，也不是营销站（营销站仍在 `apps/website`，已迁出）。

登录只有 Google 与 GitHub，走 Supabase Auth 的 PKCE。没有匿名体验登录，也没有「开始使用」。未登录时只显示登录页。`/auth/callback` 在服务端换票。访问令牌和刷新令牌只放在 HttpOnly、Secure、SameSite=Lax 的 cookie 里；浏览器脚本读不到。`is_anonymous !== false` 的身份会被拒绝。edge 请求经 `/api/edge` 由服务端附上 `Authorization` 和 `apikey`。

对话正文只留在内存里，刷新后不再显示。这和 iOS 一样：历史在设备上（SwiftData），edge 只有按 id 读取，没有对话列表。网页不另做服务端历史。可选的本机索引只存对话 id 和时间，不存正文或标题。同一账户换浏览器看不到这些对话（A03 与 iOS 等价，不是跨浏览器同步）。

退出登录会清掉 cookie，并清掉 `localStorage` / `sessionStorage` 里所有 `mimi.web.*`，以及同名前缀的 Cache Storage 和 IndexedDB。大约 15 分钟无操作会锁定；页面隐藏时会遮住内容。`prefers-reduced-motion` 时不做模糊动画。

## 环境变量

只从环境读取，仓库里的 `.env.example` 值为空。不要写入 service role、数据库密码或模型密钥。这些变量只在服务端路由里读取，不打进浏览器包。`NEXT_PUBLIC_` 名称是为了和现有部署约定兼容。

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
- `npm run test:e2e`：Playwright，先 `next build` 再 `next start`。未登录、匿名标记被拒绝、已登录后的育儿空态。不访问线上 Supabase。`MIMI_WEB_E2E=1` 只在回环地址上接受测试 cookie。
- `npm run build`

## 这一批做什么

对话走和 iOS 相同的 edge 路径与请求头（`X-Catpaw-Route`、`X-Catpaw-Model`、`X-Catpaw-Live-Text`、审批 / UI / 记忆合同）。不发送 `X-Catpaw-Device-Tools` 或 `X-Catpaw-Device-Contract`。

今天 / 成长 / 我们家只保留外壳、导航和空态。孩子的信息只存在手机上的小咪里，网页不保存、不同步孩子资料。「添加孩子」不会写入孩子。

## Web 难以等价

Live Activity、小组件、Siri、EventKit / 通讯录、仅限设备的语音识别、本机提醒排程、保存到照片。朗读使用浏览器 `speechSynthesis`。
