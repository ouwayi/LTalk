# LTalk 当前状态与开发交接

更新于 **2026-11-15（Asia/Shanghai）**。工作目录为 `D:\Agent\AllProjects\LTalk`，核对时分支为 `master`，未配置远端。当前功能基线为 `74aa291`，使用 **Java 17 目标字节码、Swing、Maven 和协议 v6**。接续时先检查实际 Git 状态和提交，再按用户的新需求继续。

## 从哪里接手

1. 阅读 [AGENTS.md](../AGENTS.md) 和本页，再看 [README](../README.md) 的构建与操作。通过 `git status --short` 和 `git log -5 --oneline` 核对现场。
2. 改功能前按需查 [需求与用户流程](requirements.md)、[架构](design.md)、[界面设计](ui-design.md) 和 [协议](protocol.md)，再核对对应代码与测试。
3. [测试报告](test-report.md) 以日期和阶段区分证据，[开发日志](DEVLOG.md) 保留决策与变更详情。根目录的[任务拆解](../局域网聊天项目任务拆解.md)和[早期草稿](devlog-early-draft.md)属于历史资料。
4. `target/`、`.tools/` 和默认 `data/` 为本机构建、证据或数据目录，均不提交。验证使用独立临时数据目录。运行已有账号时，用原数据目录，或通过 `-Dltalk.dataDir` 指定其绝对路径。

新对话可以直接发送：

> 请继续开发 `D:\Agent\AllProjects\LTalk`。先按 AGENTS.md 阅读 docs/STATUS.md 和 README.md，检查 Git 状态，再根据我接下来提出的需求继续。

## 当前功能

| 范围 | 行为与边界 |
| --- | --- |
| 连接与登录 | 启动页先连接服务器，再注册或登录。预填地址自动连接，注册成功后自动登录。双向心跳检测静默失联，故障后手动重连。 |
| 群聊与私聊 | 单一公共聊天室，私聊仅双方。消息以服务端回显为准，用户路由键为区分大小写的用户名。同名昵称可通过悬停确认用户名。私聊对象离线时保留会话和草稿，重新上线后需明确重选才能发送。 |
| 账号切换 | 候选使用独立连接，登录成功后才移交并清理旧界面资料。取消或失败保留原会话。退出登录清除窗口内的历史和草稿，服务端本人归档保留。 |
| 个人信息 | 左下角本人头像 →「个人信息」。能改昵称、上传或重置头像、修改密码。改密码需验证当前密码和确认新密码，成功后本次连接继续，下次使用新密码。 |
| 头像和昵称 | 登录、资料修改和本人历史加载同步展示资料。联系人、私聊标题、气泡和群聊摘要读取当前资料。连续消息组首显示昵称，末尾显示一个头像，滚动到边缘时一起裁切。 |
| 头像规格 | 原图限 12 MiB 和 2400 万像素。短边至少 512 像素则裁剪为 512×512，否则为 256×256，编码上限 128 KiB。旧 48/144 像素头像仍可用，重新上传原图可提高分辨率。 |
| 历史和删除 | 登录后恢复本人授权归档，支持重载、本人逐条删除和清空当前会话。本人离线期间未收到的消息、上线前公共消息均不补录，他人的副本和已导出的文件不随本人删除改变。 |
| 导出 | 当前会话菜单打开另存为，选择 JSON/CSV、文件名和位置。JSON 为合法数组，每条一行。目标文件存在时确认覆盖。Windows 使用原生文件选择器，其他系统使用 Swing 选择器。 |
| 外观与布局 | 浅色、近黑深色、预设或自选纯色/渐变。拖动色盘或输入 HEX 可预览，外观参数单独保存在本机。聊天小于 760 逻辑像素改单列，启动页小于 660 时改为上下排列并可滚动。 |

附件、表情面板、好友与创建群组入口为占位。离线投递、自动重连、已读回执、多群、聊天文件传输、TLS、限流和找回密码尚未实现。联系人列表展示在线用户和已有会话。

## 关键约定

- 上下文边界以 `AGENTS.md` 为准。保持 Java 17 兼容。连接、收发、密码计算、用户图片文件处理和导出在后台，Swing 和会话展示状态在 EDT。
- 服务端和客户端一起升级到协议 v6。TCP 使用 UTF-8/LF JSON，普通帧 16 KiB，四种头像相关帧 176 KiB，正文最长 2000 个 UTF-16 单元。密码为 6～128 个 UTF-16 单元，保留空白。协议没有版本协商。
- 默认监听 `0.0.0.0:9000`，最多 20 个连接，含未认证连接。初始握手或直接登录期限为 10 秒，心跳默认间隔 10 秒、响应期限 20 秒。仅匹配的 PONG 完成本轮探测，实际时间受调度影响。
- 登录态绑定 TCP。账号保存随机盐与 PBKDF2-HMAC-SHA256 散列，服务端持有数据目录锁。资料与改密先原子写文件再发布结果，历史和导出按认证账号隔离。
- 背景保持可选纯色或渐变，浅深色与窄屏均可用。外观只写 `${user.home}/.ltalk/appearance.properties`，项目采用自身的中性描述。

## 代码入口

| 路径（Java 部分相对 src/main/java/cn/ltalk） | 职责 |
| --- | --- |
| common/Message、MessageCodec、MessageType、Validation | 帧、消息模型和输入校验 |
| common/ConversationScope、UserProfile、AvatarData | 会话键、本人资料、头像格式约束 |
| server/AccountStore、ChatArchive | 账号、改密、展示资料和本人归档 |
| server/MessageRouter、ClientSession、SessionRegistry、ChatServer | 握手与认证、会话权限、路由、心跳、连接清理 |
| client/ChatClient、ClientSessions、HistoryChannel、HistoryExport | 后台连接、候选账号交接、历史分页和导出 |
| client/ChatWindow、client/ui/WelcomePanel、ConnectionPanel、AuthenticationPanel | 窗口组装、连接及认证界面 |
| client/ContactProfile、AvatarImporter、client/ui/ProfileAvatar、ProfilePanel、PasswordChangePanel | 头像导入、展示资料、本人资料编辑及改密 |
| client/ui/ConversationStore、ConversationId、ConversationList、MessageTimeline、ResponsiveShell | EDT 展示、会话选择、分组气泡与布局 |
| client/ui/Appearance*、ColorPicker、ChatTheme、ThemePalette、FileDialogs、WindowsFilePicker | 外观与色盘、主题、系统文件对话框 |
| pom.xml、build.ps1 | Java 17 编译目标、固定 Maven 3.9.9、含依赖打包 |

## 已有验证与后续核对

- 功能基线 `74aa291` 于 2026-11-15 执行 `powershell -NoProfile -File .\build.ps1 -o package`，**104 项通过，0 失败、错误或跳过**，约 1:23。Windows、JDK 25.0.4、Maven 3.9.9，以 release 17 编译，包含回环 TCP、临时数据与 Swing 组件验证。构建产物为 `target/ltalk-1.0.0.jar`，本机日志为 `.tools/password-change-final.log`。本次文档交接未重新执行功能回归。
- 最近功能提交为 `74aa291` 改密、`d629d5e` 高清头像、`c4840f3` 资料同步、`984cb5b` 消息组头像裁切。此前阶段的测试与历史人工反馈见 [测试报告](test-report.md)。
- 当前 v6 的多设备流程、独立 JDK 17 运行时、各档 DPI、其他系统和长时间负载尚未完整验收。早期用户反馈的宿主机＋虚拟机验证属于当时版本。
- 同步服务端写入可能受慢客户端影响，跨账号归档写入无事务保证。窗口保留全部已加载历史，缺少容量淘汰。改密、资料保存或删除超时可能已生效，可重新登录或重载历史核对。
- 下一步由用户选定。若本轮目标是验收，可优先核对 v6 多设备资料同步、改密后重登与 256/512 头像上传。其他可选方向见 [架构](design.md) 和 [界面设计](ui-design.md)。
