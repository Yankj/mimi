import { copy as coreCopy } from "../vendor/mimi-core/index";

/**
 * Miniprogram wording. Shared strings come from mimi-core unchanged; only lines
 * that speak as the Web app (网页、浏览器、标签页、Google/GitHub) are overridden here,
 * so Web and iOS copy stay untouched.
 */
export const mpOverrides = {
  localLedger: "孩子的资料只存在 iOS 版小咪里。小程序不保存、也不同步孩子资料。",
  familyTodo: "孩子的资料只存在 iOS 版小咪里。在小程序里可以看看今天、成长和我们家，不会保存、也不会同步孩子资料。",
  addChildBlocked: "添加孩子要把昵称写进育儿账本。小程序这一步不保存、不同步任何孩子资料，等家庭同步定了再开。",
  noCalendar: "小程序不会往手机日历里加安排，也不会发提醒。记录会一直留着，直到你自己删掉。",
  chatBoundary: "只有你明确发送的内容才会交给小咪处理。这一步的回复来自本机模拟，不会发到线上。",
  reminderPage: "小程序这一步不发提醒，也不会往手机日历里加安排。",
  reminderRow: "小程序暂不发送提醒",
  lockBody: "有一阵子没操作了，请重新确认登录。",
  signOutNamed: "退出后需要重新登录。这一步的模拟会话和对话草稿会从本机清掉。",
  wechatLogin: "微信登录（模拟）",
  loginPending: "真实的微信登录还没接上，要等 AppID、已备案的转发域名和账号绑定方式定了再做。现在点按钮只会进入模拟会话，方便看界面。",
  lockPending: "「继续」只是模拟重新确认。真实的微信重新登录还没接上。",
  chatHint: "回复来自本机模拟流，不会发到线上。",
  bindEntry: "绑定已有的小咪账号",
  bindTitle: "绑定小咪账号",
  bindBody: "在 iOS 或网页版小咪里登录后生成一次性绑定码，填到这里，小程序就能用同一个账号。",
  bindField: "输入绑定码",
  bindPlaceholder: "MIMI-XXXX-XXXX",
  bindSubmit: "绑定",
  bindFormatBad: "绑定码格式不对，请检查后再试。",
  bindPending: "绑定还没接上，要等微信登录和账号绑定方式定了再做。这一步不会发出任何请求。",
} as const;

export const copy = { ...coreCopy, ...mpOverrides };
