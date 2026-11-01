# LTalk 桌面界面设计与扩展说明

客户端采用组件化的 Java Swing 桌面界面，支持一个公共聊天室和现有私聊。展示层与 ChatClient、ClientEventListener、服务端及 common 消息模型分离，界面调整不修改现有 JSON/TCP 协议，也不引入额外 Maven 依赖。

## 改造前与改造后

原界面集中在 ChatWindow：顶部 FlowLayout 连接配置，中间 JTextArea 统一显示群聊/私聊和系统消息，右侧 JList 在线用户，底部 JTextArea 与按钮。字号、颜色和边距直接写在窗口类里；没有按会话分组的本地展示状态。

界面由左侧会话栏与右侧聊天区组成：桌面侧栏宽度约为窗口的 28%，限制在 300～354 像素；右侧内容最大宽度 920 像素并居中。聊天标题栏和输入区为圆角浮动面板；对方消息居左，自己经服务端回显的消息居右。小于 760 像素时转为单列；最小窗口为 360×540。

语义色板集中于 ThemePalette，ChatTheme 负责按角色应用颜色：选中蓝为 `#3390EC`，浅色面板为白色、深色面板为 `#1B2633`；文字、输入框、气泡、系统提示、禁用状态和滚动条均跟随当前窗口主题。使用 Windows 微软雅黑 UI，消息缺失字形按 Segoe UI Emoji/逻辑字体回退。背景只绘制用户选择的纯色或渐变，不再绘制图案；图标和字母头像由 Java2D 绘制，无远程素材或额外图形依赖。

## 修改文件清单

以下路径相对于仓库根目录。

| 文件 | 变化与职责 |
| --- | --- |
| src/main/java/cn/ltalk/client/ChatWindow.java | 更新：组装界面、绑定原网络入口、处理会话切换/搜索/键盘/菜单、适配 EDT 回调 |
| src/main/java/cn/ltalk/client/ui/ConversationId.java | 新增：会话类型与稳定 key，群聊与私聊不会因名称相同混淆 |
| src/main/java/cn/ltalk/client/ui/ConversationStore.java | 新增：本地会话历史、草稿、摘要、未读、在线状态、身份切换清理和离线选择保护 |
| src/main/java/cn/ltalk/client/ui/ConnectionPanel.java | 新增：可折叠的连接配置组件，继续使用原 IP/端口/userId |
| src/main/java/cn/ltalk/client/ui/ConversationList.java | 新增：头像、昵称、摘要、时间、未读红点/徽标、在线标记、选中和悬停绘制 |
| src/main/java/cn/ltalk/client/ui/MessageTimeline.java | 新增：消息/系统提示/空状态、气泡布局、时间与滚动 |
| src/main/java/cn/ltalk/client/ui/MessageText.java | 新增：可选择复制的纯文本、字体回退、长单词换行，不解释 HTML |
| src/main/java/cn/ltalk/client/ui/ResponsiveShell.java | 新增：双栏到单列的导航与布局 |
| src/main/java/cn/ltalk/client/ui/ChatTheme.java | 颜色角色绑定、窗口主题应用、字体、圆角面板、按钮悬停、输入提示和滚动条 |
| src/main/java/cn/ltalk/client/ui/Avatar.java | 新增：按名称生成稳定颜色和字母头像，群聊使用群组图标 |
| src/main/java/cn/ltalk/client/ui/LineIcon.java | 新增：清晰的矢量操作图标和服务端回显标记 |
| src/main/java/cn/ltalk/client/ui/WallpaperPanel.java | 绘制纯色/渐变背景，移除纹样 |
| src/main/java/cn/ltalk/client/ui/AppearanceSettings.java | 浅色/深色模式、独立背景色、纯色/渐变、色值校验和预设配色 |
| src/main/java/cn/ltalk/client/ui/ThemePalette.java | 面板、文本、输入、消息和错误等语义色板 |
| src/main/java/cn/ltalk/client/ui/AppearancePreferences.java | 本机外观配置加载、格式/大小校验与原子保存 |
| src/main/java/cn/ltalk/client/ui/AppearancePanel.java | 外观草稿编辑、颜色输入、实时预览、应用/取消/恢复默认 |
| src/main/java/cn/ltalk/client/ui/ChatPopupMenu.java | 所有下拉菜单共享的圆角、阴影、图标、悬停和禁用状态 |
| src/test/java/cn/ltalk/client/ChatWindowTest.java | 更新：保留原连接/错误/私聊离线/回显断言，增加会话、草稿、搜索、未读、键盘、窄屏和预览 |
| src/test/java/cn/ltalk/client/ui/ConversationStoreTest.java | 新增：数据分组、未读、离线重选、身份隔离及搜索 |
| src/test/java/cn/ltalk/client/ui/MessageTimelineTest.java | 新增：纯文本不被 HTML 解释、长串换行与滚动到底部 |
| src/test/java/cn/ltalk/client/ui/AppearanceSettingsTest.java | 色值边界、模式独立性与纯色/渐变像素验证 |
| src/test/java/cn/ltalk/client/ui/AppearancePreferencesTest.java | 配置往返、损坏/超大文件回退、保存失败与临时文件清理 |
| src/test/java/cn/ltalk/client/ui/AppearancePanelTest.java | 颜色输入校验、预览模式、应用/取消和恢复默认 |
| src/test/java/cn/ltalk/client/ui/ChatPopupMenuTest.java | 菜单动作、禁用项、悬停绘制及窗口间色板隔离 |
| README.md | 更新：新界面的操作、快捷键、窄屏导航和本说明入口 |
| docs/design.md | 更新：界面状态与组件分工 |
| docs/test-report.md | 更新：新增 UI 回归与真实窗口验收记录，保留先前验收结果 |
| docs/course-report.md | 更新：当前测试数与真实窗口 UI 验证结论 |
| docs/ui-design.md | 新增：本说明 |

## 交互规则

- 左侧搜索匹配会话名称与最后一条消息摘要，隐藏列表项不会切换当前发送对象。昵称当前使用协议中的 userId，头像是本地生成图形，不伪造头像 URL 或服务器昵称字段。
- 一条服务端群聊事件只进入公共聊天室；私聊按双方身份进入对应联系人会话。不同用户使用相同 requestId 不会被当作重复消息丢弃。
- 正式气泡只来自服务端事件，提交发送不会生成第二份假消息。时间戳旁单勾仅表示收到服务端回显，工具提示明确不是已读回执。
- 自己发送的消息不计未读；后台窗口、未选中的会话或窄屏列表页收到对方消息时累加未读。实际打开会话并可见后清零。
- 输入框支持 Enter/Ctrl+Enter 发送、Shift+Enter 换行，随文本增高并在达到上限后内部滚动。发送后清空已提交文本、滚动到最新消息；输入校验失败保留草稿。
- 草稿按会话保存。选中的私聊对象离线后，保持该聊天页与草稿、禁用发送；不会退回群聊，目标重新上线也需要明确重选。同名手动重连保留本窗口历史；成功切换登录身份清除上一身份的私聊历史和草稿。
- 连接设置在独立启动页显示；登录后进入聊天页，左上菜单可返回连接页，已登录可返回聊天。侧栏底部改为本人资料入口。拒绝登录、连接失败、断开、服务端停止与手动重连保留明确状态。
- 搜索当前会话只过滤本地已接收记录，不请求服务器历史。错误显示在状态栏和界面提示中；既有网络 API 不返回发送请求编号，界面不虚构错误归属或消息送达状态。
- 工具栏附件、表情面板、好友和创建群组入口会明确提示“尚未接入”，不会发送不存在的协议请求。窄屏聊天页底部同样显示工具反馈。
- 时间线复用当前会话已有的气泡，并按可用宽度缓存测量结果，避免每次收消息重新创建所有富文本组件。复用按事件对象区分，不以内容、时间或 requestId 去重；切换身份或会话后丢弃不再显示的组件缓存。
- 外观切换仅更新展示状态与色板，不重建 ChatClient 或 ConversationStore；已有消息的气泡缓存按色板失效，确保旧消息也正确换色。不同窗口通过各自根组件持有色板，不使用全局可变主题。

## 外观设置与保存

通过「菜单 → 外观设置」打开设置；主菜单另有一键浅色/深色切换。用户可以选择纯色或渐变、预设颜色，或输入任意不透明 `#RRGGBB` 色值。设置面板维护草稿并实时预览，仅点击应用才发布给聊天窗口，取消/关闭不发布；非法色值提示错误并禁用应用。纯色模式停用结束色字段。

AppearanceSettings 分别保存浅色和深色背景色，模式切换不覆盖另一个模式的颜色；背景类型共用。AppearancePreferences 将版本、模式和色值写入系统用户目录 `.ltalk/appearance.properties`，通过同目录临时文件与替换避免留下半份配置。读取遇到损坏、未知版本或超过 32 KiB 的文件时回退默认，不主动覆盖原文件。保存失败时外观仍可使用，并显示错误；不影响聊天草稿。

设置为当前系统用户共享的本机偏好，新窗口启动时读取最近保存值；已运行窗口保持各自的显示状态。自动化测试使用临时目录，真实窗口验收使用独立测试用户目录，不覆盖实际用户配置。

## 后续功能接入位置

```text
既有 ClientEventListener → ChatWindow 适配 → ConversationStore → Summary / Entry → Swing 组件
界面发送 / 工具动作   → ChatWindow 适配 → 既有 ChatClient
```

1. **好友/昵称/头像**：在独立联系人数据层提供元数据，以 ConversationId.key 关联；将元数据映射到 Summary.title 和头像组件即可。不要把 userId 的路由意义与显示昵称合并，也不要把好友网络请求写进列表绘制代码。
2. **多个群聊**：ConversationId 已区分 GROUP/PRIVATE，GROUP.key 可表达不同群。当前协议没有 groupId，收到的 GROUP_MESSAGE 仍仅映射 PUBLIC。未来后端支持多群后，在窗口适配层/Store 的群聊入口补充群 ID 映射，复用列表和时间线；现阶段不会把假群入口路由到公共广播。
3. **附件/表情**：ChatWindow 的 attach/emoji 监听器和 showPendingTool 是可替换入口；附件需独立传输服务和消息呈现类型，表情面板可先只向编辑器插入 Unicode。不要改变纯文本网络入口来伪装文件已发送。
4. **持久化/历史**：Store 当前仅为 EDT 内存状态，Summary/Entry 是只读快照。未来可增加仓储层，先完成身份和私聊访问控制，再决定历史的读写范围。普通重连不自动补发旧草稿或历史消息。
5. **主题/布局**：颜色角色改 ThemePalette，样式绑定改 ChatTheme，外观选项与持久化分别改 AppearanceSettings/AppearancePreferences，背景绘制改 WallpaperPanel，菜单改 ChatPopupMenu，气泡改 MessageTimeline/MessageText，窄屏断点改 ResponsiveShell；不需要改 TCP 线程或服务端路由。

## 运行与验收

按 README 构建 `powershell -NoProfile -File .\build.ps1`，然后分别运行服务端和 `cn.ltalk.client.ChatWindow`。在客户端点击连接即可查看真实数据；不会自动生成截图示例聊天。

自动化生成的界面预览位于 `target/ui-preview/ui-desktop.png`、`ui-narrow-chat.png`、`ui-narrow-list.png`，它们使用测试会话数据，不提交 Git。真实 Windows 窗口的 computer-use 验收过程与最终测试数见 [测试报告](test-report.md)。

另有暗色与菜单预览：`ui-dark-desktop.png`、`ui-dark-narrow.png`、`menu-light.png`、`menu-dark.png`、`appearance-settings.png`，均在同一预览目录。

## 账号与导出入口（2026-10-29，历史方案，已由下节替代）

ConnectionPanel 新增密码输入与注册按钮；注册弹窗填写昵称，用户名/密码沿用连接表单，可选择随机用户名，成功返回后填回用户名而不自动登录。主菜单新增「导出聊天记录」，登录后可选择 JSON/CSV 和目标目录。网络、密码验证和导出文件写入在后台，状态/完成/错误经原回调执行器更新 EDT；不将密码保存进外观配置或聊天记录。列表继续按用户名显示，昵称仅保存为账号资料。

组件回归已通过；本轮未执行注册/导出原生弹窗和多机操作，未改变主题、背景或现有占位功能。协议已扩展至 v2，旧版无密码客户端须升级。

## 会话菜单调整（2026-10-30）

导出入口移至聊天上方会话菜单，只导出发起时选定的群聊或私聊。主菜单保留连接、外观设置，移除导出；会话菜单移除连接、外观入口，保留回到最新消息。外观设置移除顶部两句介绍文字，保留预览及所有配色操作。

## 独立启动页与个人信息（2026-10-30）

启动页左侧只有服务端 IP/端口和连接/断开，右侧默认为登录，可用「还没有账号？立即注册」切换为昵称、手填/随机用户名、密码和确认密码。预填地址时自动连接服务器；注册成功自动登录，登录成功才显示聊天页。桌面左右排列，窄屏上下排列且整体可滚动；配色跟随原有窗口主题。

聊天列表底部改为可点击的 UserIdentityButton，显示本人头像、昵称和 @用户名；无身份时显示未登录，移除独立「显示连接设置」按钮。点击菜单含个人信息/退出登录/切换账号。ProfilePanel 显示用户名、昵称、头像、注册时间和服务器；支持上传图片、恢复默认头像和编辑昵称，确认持久化后更新用户栏。个人信息窗口可缩放并滚动；新资料不自动更改其他联系人显示。

退出登录清除窗口身份和聊天/草稿，回到登录页保留用户名。切换账号改为保留旧连接与消息/草稿，清空认证表单输入，候选验证成功后才退出旧账号；右上角返回聊天取消候选，失败也不退出旧账号。密码在取消/成功后清空并隐藏。连接设置入口显示启动页，当前仍登录时可返回聊天。无网络故障自动重连或自动凭证保存。

本轮组件渲染预览新增 welcome-login/register/dark/narrow/narrow-bottom、profile-footer/editor，均在忽略的 target/ui-preview；这是自动化组件与本机网络验证，不能当作用户人工验收或多机证据。

## 文件选择与密码显隐（2026-10-31）

头像与导出目录在 Windows 调用系统现代 Common Item Dialog，左侧导航、顶部路径及搜索栏由系统绘制，跟随系统外观；非 Windows 暂用原选择器。取消不导入/导出，失败提示可重试。密码和确认密码右侧各有显示/隐藏按钮，默认遮罩；换表单/清凭据/返回聊天后隐藏。

## 保留会话的账号切换（2026-10-31）

切换页标题为「切换账号」，提交按钮为「登录并切换」；原账号在线时右上角「返回聊天」始终可用，包括新账号验证中。待切换连接的错误只在表单显示，不写入原聊天记录；返回保留旧消息/草稿/未读，恢复旧地址；成功清除旧身份展示再显示新身份，用户名相同但服务端不同也隔离。原连接自身断线时返回按钮隐藏，不假装旧聊天仍可使用。

## 导出另存为（2026-11-01）

会话菜单导出直接进入保存窗口，不先选格式；同窗选择 JSON/CSV 与文件名/位置。Windows 用 IFileSaveDialog，其他系统用 Swing 保存窗口；按类型整理扩展名并对最终路径确认覆盖，确认前不开始写出。
