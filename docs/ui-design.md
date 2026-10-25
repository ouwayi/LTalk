# LTalk 桌面界面设计与扩展说明

客户端采用组件化的 Java Swing 桌面界面，支持一个公共聊天室和现有私聊。展示层与 ChatClient、ClientEventListener、服务端及 common 消息模型分离，界面调整不修改现有 JSON/TCP 协议，也不引入额外 Maven 依赖。

## 改造前与改造后

原界面集中在 ChatWindow：顶部 FlowLayout 连接配置，中间 JTextArea 统一显示群聊/私聊和系统消息，右侧 JList 在线用户，底部 JTextArea 与按钮。字号、颜色和边距直接写在窗口类里；没有按会话分组的本地展示状态。

新版是左侧白色会话栏＋右侧浅绿纹样聊天区：桌面侧栏宽度约为窗口的 28%，限制在 300～354 像素；右侧内容最大宽度 920 像素并居中。聊天标题栏和输入区为白色圆角浮动面板；对方消息为左侧白色气泡，自己经服务端回显的消息为右侧浅绿气泡。小于 760 像素时转为单列；最小窗口为 360×540。

色板集中于 ChatTheme：选中蓝 `#3390EC`，深色文字 `#1C2731`，次要文字 `#84919C`，输入背景 `#F2F5F7`，发出气泡 `#E1FFC7`。使用 Windows 微软雅黑 UI，消息缺失字形按 Segoe UI Emoji/逻辑字体回退。壁纸、图标、字母头像均由 Java2D 绘制，无远程素材、图片生成依赖或截图中第三方聊天内容。

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
| src/main/java/cn/ltalk/client/ui/ChatTheme.java | 新增：颜色、字体、圆角面板、按钮悬停、输入提示和滚动条 |
| src/main/java/cn/ltalk/client/ui/Avatar.java | 新增：按名称生成稳定颜色和字母头像，群聊使用群组图标 |
| src/main/java/cn/ltalk/client/ui/LineIcon.java | 新增：清晰的矢量操作图标和服务端回显标记 |
| src/main/java/cn/ltalk/client/ui/WallpaperPanel.java | 新增：低对比度原创矢量纹样背景 |
| src/test/java/cn/ltalk/client/ChatWindowTest.java | 更新：保留原连接/错误/私聊离线/回显断言，增加会话、草稿、搜索、未读、键盘、窄屏和预览 |
| src/test/java/cn/ltalk/client/ui/ConversationStoreTest.java | 新增：数据分组、未读、离线重选、身份隔离及搜索 |
| src/test/java/cn/ltalk/client/ui/MessageTimelineTest.java | 新增：纯文本不被 HTML 解释、长串换行与滚动到底部 |
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
- 连接设置初次显示，成功连接后折叠；左上菜单/侧栏底部入口可再次打开。原有拒绝登录、连接失败、主动断开、服务端停止和手动重连继续走原网络实现。
- 搜索当前会话只过滤本地已接收记录，不请求服务器历史。错误显示在状态栏和界面提示中；既有网络 API 不返回发送请求编号，界面不虚构错误归属或消息送达状态。
- 工具栏附件、表情面板、好友和创建群组入口会明确提示“尚未接入”，不会发送不存在的协议请求。窄屏聊天页底部同样显示工具反馈。
- 时间线复用当前会话已有的气泡，并按可用宽度缓存测量结果，避免每次收消息重新创建所有富文本组件。复用按事件对象区分，不以内容、时间或 requestId 去重；切换身份或会话后丢弃不再显示的组件缓存。

## 后续功能接入位置

```text
既有 ClientEventListener → ChatWindow 适配 → ConversationStore → Summary / Entry → Swing 组件
界面发送 / 工具动作   → ChatWindow 适配 → 既有 ChatClient
```

1. **好友/昵称/头像**：在独立联系人数据层提供元数据，以 ConversationId.key 关联；将元数据映射到 Summary.title 和头像组件即可。不要把 userId 的路由意义与显示昵称合并，也不要把好友网络请求写进列表绘制代码。
2. **多个群聊**：ConversationId 已区分 GROUP/PRIVATE，GROUP.key 可表达不同群。当前协议没有 groupId，收到的 GROUP_MESSAGE 仍仅映射 PUBLIC。未来后端支持多群后，在窗口适配层/Store 的群聊入口补充群 ID 映射，复用列表和时间线；现阶段不会把假群入口路由到公共广播。
3. **附件/表情**：ChatWindow 的 attach/emoji 监听器和 showPendingTool 是可替换入口；附件需独立传输服务和消息呈现类型，表情面板可先只向编辑器插入 Unicode。不要改变纯文本网络入口来伪装文件已发送。
4. **持久化/历史**：Store 当前仅为 EDT 内存状态，Summary/Entry 是只读快照。未来可增加仓储层，先完成身份和私聊访问控制，再决定历史的读写范围。普通重连不自动补发旧草稿或历史消息。
5. **主题/布局**：样式改 ChatTheme，背景改 WallpaperPanel，气泡改 MessageTimeline/MessageText，窄屏断点改 ResponsiveShell；不需要改 TCP 线程或服务端路由。

## 运行与验收

按 README 构建 `powershell -NoProfile -File .\build.ps1`，然后分别运行服务端和 `cn.ltalk.client.ChatWindow`。在客户端点击连接即可查看真实数据；不会自动生成截图示例聊天。

自动化生成的界面预览位于 `target/ui-preview/ui-desktop.png`、`ui-narrow-chat.png`、`ui-narrow-list.png`，它们使用测试会话数据，不提交 Git。真实 Windows 窗口的 computer-use 验收过程与最终测试数见 [测试报告](test-report.md)。
