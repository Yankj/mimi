# 语音优先输入栏 v0.6（S1b）

状态：草稿 PR，**未合并**。分支 `feat/voice-composer`。范围只有育儿「今天」「成长」两页的底部输入栏；不改 ParentingStartAction、领域命令、设置、「我们家」。
证据级别：模拟器 + 脚本化转写器。**没有任何真实麦克风、真实语音识别、真实权限弹窗的证据。** Mock ≠ acceptance。

## 行为

- 默认语音：空闲时是一条大的「按住说话」胶囊，左边是键盘切换键（`parenting.composer.keyboardToggle`），草稿非空且没在听时右边出现发送键。
- 按住说话（≥0.3 秒）：上滑发送，左滑取消（左优先于上），松手后文字留在可编辑框里，不自动发送。轻点胶囊开始，再点一下结束。
- 实时转写进上方可编辑文字框（`parenting.composer.transcript`）；用户在框里打字，听写立刻让位，之后的转写不会覆盖手打的字。
- 键盘态：字段为空时右侧是小麦克风，有字变发送；听写中麦克风变波形，文字直接进输入框。
- 写入前确认不变：转写 → 意图 → 确认卡。上滑发送也只是交给确认卡，没有绕过。
- 识别默认只在本机：`startDictation(onDeviceOnly: true)`，没有本机能力时以 `.unsupported` 结束，**不回退到 Apple 服务器**，界面提示改用键盘（系统键盘自带的麦克风听写）。聊天里的听写保持原来的分层引擎（含服务器层），不受影响。
- 权限只在首次按麦克风时申请，且先显示一句应用内说明（`parenting.composer.voiceExplain`），点「继续」才触发系统弹窗；选「改用键盘」则什么都不申请。说明只出现一次（`mimi.voice.permissionExplained`）。
- 非正常状态一律是行内提示，不弹窗：权限被拒（去设置）、本机识别不可用（改用键盘）、没听到内容、周围太吵/识别失败、被打断（保留已听到的文字）、进后台（停止并保留文字）。
- 减少动态效果：波形换成静态条 + 计时；VoiceOver：只需轻点（双击开始/结束），取消、发送是自定义操作，不要求按住。

## 复用与新增

复用：`ComposerDictation`（改成通过 `DictationDraftSink` 写草稿，聊天行为不变）、`NativeVoiceController.startDictation` 的分层引擎与全部「聋听写」保护（`.default` 会话模式、tap 重装、分段续接、看门狗，均未改动）、`DictationPolicy.join`、`DictationTranscript`。

新增：
- `DictationDraftSink` / `DictationTranscribing` 协议（`Composer/DictationDraftSink.swift`）：`ConversationFirstModel` 与 `ParentingNaturalInputModel` 都是 sink；`NativeVoiceController` 是真实转写器。
- `NativeVoiceController.startDictation(onDeviceOnly:onText:onEnd:)`：每次会话的开关，默认关；旧入口调用它传 `false`；会话结束时复位。
- `ScriptedDictationTranscriber`（仅 DEBUG）：脚本化的 partial/final/超时/权限拒绝/不可用/静音/打断。
- `ParentingVoiceControls.swift`：全部新界面（胶囊、波形、说明、提示、状态）。输入栏里只改了 `ParentingNaturalInputViews.swift` 的一处接入（把原来的 HStack 换成 `ParentingVoiceInputRow`，原 TextField 与发送键作为闭包原样传入，id 不变）。
- InfoPlist（中英）与两份 Info.plist 默认值：说明小咪语音输入只在本机识别。

踩到的坑：视图里对「每次求值都新建的 Publisher」做 `onReceive`，遇到会回放当前值的 subject 会无限重渲染（主线程 99% CPU）。现在由 `ParentingLevelHistory` 订阅一次。

## 测试

见 PR 描述里的结果。单元：`VoiceFirstComposerTests`（33 项，脚本化转写器 + 假 sink：实时填入、松手保留、上滑只发一次、左滑还原且不提示、打字接管、一次只允许一个会话、sink 规整化、拒绝/不可用/静音/噪声/打断/超时、说明只出现一次、模式切换、聊天不变）。UI：`ParentingVoiceUITests`（11 项）。

## 没有验证的（必须真机）

- 真实麦克风收音、真实 `SpeechAnalyzer` / `SFSpeechRecognizer` 本机识别质量、中文识别准确率、嘈杂环境。
- 首次权限的真实系统弹窗顺序与文案（模拟器没有麦克风；说明句只在假转写器下验证了出现时机）。
- iOS 26 上 SpeechAnalyzer 语言包下载（「正在准备语音模型…」）在真机上的表现。
- 来电、Siri、AirPods 切换、锁屏、后台等真实打断。
- 真实音量驱动的波形（模拟器里是脚本的正弦曲线）；触觉反馈。
- 按住手势在真机上的手感、与键盘弹出/收起、浮动标签栏的相互作用。
- 模拟器是 iOS 27.0，没有 iOS 26 运行时；玻璃效果的最终样子由另一条（S1 玻璃）分支负责，这里的输入栏只沿用现有 `parentingGlass`，合并时会有布局冲突要解。
- 键盘态截图里有 iOS 首次使用键盘的滑动输入提示，非应用内容。
- `scripts/check-localization.py` 在 main 上本来就在 InfoPlist 键对比处断言失败（`CFBundleDisplayName`/`CFBundleName` 多于 UsageDescription 键），与本改动无关；其余本地化检查通过。
