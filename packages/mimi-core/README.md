# @mimi/core

Web 和小程序共用的纯逻辑：请求头、路径和合同（`wire.ts`）、以及从 iOS 文案抄来的中文字符串（`copy.ts`）。不依赖浏览器、Next.js 或微信运行时。

Web 通过 `apps/web/lib/wire.ts` 和 `copy.ts` 再导出，对外行为与抽出前一致。微信小程序把这份源码同步到 `apps/miniprogram/miniprogram/vendor/mimi-core`，以便在开发者工具里编译（微信不允许引用 `miniprogramRoot` 之外的文件）。

```bash
npm ci
npm run typecheck
npm test
npm run sync-miniprogram-vendor
```

不要在这个包里放密钥、真实后端地址或平台 API 调用。
