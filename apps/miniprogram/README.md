# mimi 微信小程序（骨架）

对照 [issue #157](https://github.com/Yankj/mimi/issues/157)。这一步只搭界面和本地模拟，不接真实后端，不碰生产。

用原生微信小程序加 TypeScript，开发阶段使用测试 AppID `touristappid`。仓库里不写正式 AppID，也不写密钥。

在微信开发者工具里打开本目录（`apps/miniprogram`），即可用测试 AppID 预览。共享逻辑来自 `packages/mimi-core`，已同步到 `miniprogram/vendor/mimi-core`（微信不能引用 `miniprogramRoot` 之外的文件）。改了 `@mimi/core` 之后请在 `packages/mimi-core` 里运行 `npm run sync-miniprogram-vendor`。

## 这一步有什么

- 登录页：微信登录按钮接模拟会话。页面上写明真实登录还没定。
- 对话页：走统一的 `request` 封装和 `@mimi/core` 的请求头构造，回复是本机模拟的流式增量。不会发到线上。
- 底部三个 Tab「今天 / 成长 / 我们家」只做外壳、导航和空态，文案与 Web 第一批一致。「添加孩子」只弹出说明，不写入任何孩子资料。
- 大约 15 分钟没有操作，会在 `onHide` / `onShow` 里锁定；退出登录会清空本机模拟会话和对话草稿。

## 在 Linux 上可以跑的检查

不需要安装微信开发者工具：

```bash
cd apps/miniprogram
npm ci
npm run typecheck
npm test
```

## 网络约定

所有请求都走 `miniprogram/utils/request.ts`。`PLACEHOLDER_BASE_URL` 是 `https://mimi-miniprogram-api.example.invalid`，只是占位。默认传输层不会发 HTTP，并且会拒绝 `*.supabase.co`、含 `catpaw` 的主机，以及 `/functions/v1/` 这类线上路径。

## 未定项

这些要用户点头之后才能接真实后端，不在这一步实现：

- 登录方式：拟定是微信登录后绑定到同一个 Mimi 账号，绑定码由 Web 或 iOS 生成。签发会话的 edge 函数要单独做安全评审。
- 用用户的主体注册小程序 AppID。
- 已备案、并配进小程序后台白名单的 HTTPS 转发域名。这个域名不只是反代，还要承担 Web 服务端现在为 `/api/edge` 代为附上令牌的工作。
- 对话历史的本地加密方案：小程序没有 IndexedDB，本地存储大约 10MB。是在 JS 里实现 AES-GCM，还是用微信的用户加密密钥，这一步只记录问题，不落地。因此本批对话只放在内存里，锁定或退出即清空。

## 不在本批

部署生产、修改线上 edge、跑 `cartoon_ecopy` 迁移、发明家庭同步后端、注册正式 AppID、提交微信审核。提醒、朗读、语音输入、设备能力、卡通电子稿也不做。

## 提审前提醒

小程序里提供 AI 对话，微信审核一般要选对服务类目，可能还要出示生成式 AI 或深度合成相关的备案材料。具体要求以提审时微信后台的类目说明为准。
