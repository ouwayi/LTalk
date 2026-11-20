# LTalk 当前状态与开发交接

更新于 **2026-11-20（Asia/Shanghai）**。核对时分支为 `master`，跟踪 `origin/master`。使用 **Java 17 目标字节码、Swing、Maven、SQLite 表结构 v2、TCP 协议 v7 和 UDP 发现 v1**，提供可选 TLS 公网模式。接续时在仓库根目录核对 Git 状态和提交。

最近功能提交为 `2b69ce1`（阶段 51，启动页连接与账号填写说明），本机完整离线回归 **152 项通过**。近期功能包括 60 秒撤回、10 位群号、信息卡片复制群号和用户名，以及从完整群成员名单筛选和选择新管理员。完整构建使用 `powershell -NoProfile -File .\build.ps1 -o package`，产物为 `target/ltalk-1.0.0.jar`。[近期桌面交互回归](test-report.md#近期桌面交互回归)

课程交付包含可独立分享的 [Java 多人聊天室开发与验证技能](../.agents/skills/java-chatroom-engineering/SKILL.md)。阶段 53 根据课程要求与开发对话补充了 [规格和评测模板](../.agents/skills/java-chatroom-engineering/references/evaluation-playbook.md) 及 [15 个 LTalk 案例](../.agents/skills/java-chatroom-engineering/references/ltalk-example.md)。仓库内技能与独立副本均通过校验。阶段 53 的本机完整离线回归为 152 项通过，云端与真实高 DPI 仍按原验证范围记录。详见 [阶段 53 开发日志](DEVLOG.md#阶段-53-充实课程规格驱动开发技能2026-11-19本轮记录)。

## 从哪里接手

1. 阅读 [AGENTS.md](../AGENTS.md) 和本页，再看 [README 快速开始](../README.md#快速开始)。实际操作见[用户指南](user-guide.md)。用 `git status --short --branch` 和 `git log -5 --oneline` 核对分支、工作区和功能基线。
2. 改功能前按需查 [需求与用户流程](requirements.md)、[架构](design.md)、[界面设计](ui-design.md) 和 [协议](protocol.md)，再核对对应代码与测试。
3. [测试报告](test-report.md) 以日期和阶段区分证据，[开发日志](DEVLOG.md) 保留决策与变更详情。[任务拆解](局域网聊天项目任务拆解.md)记录最初的课程项目范围和验收计划。
4. `target/`、`.tools/`、`data/` 和 `data-sqlite/` 为本机构建、证据或数据目录。README 的同机演示使用 `target/demo-data/`，其他验证也使用隔离目录。[本地启动参数与默认数据目录](deployment.md#数据目录与运行参数)
5. 选择下一项开发工作时，查阅 [后续开发路线图](ROADMAP.md)。下一项功能由用户指定。云端复测按[更新 JAR 与复测](deployment.md#更新-jar-与复测)操作。

阶段 54 整理了文档目录和路径示例。早期草稿的来源可从 [开发日志](DEVLOG.md#记录口径与来源) 查到。部署示例使用 SSH 登录账号的主目录接收上传文件，systemd 仍使用固定的安装、数据和证书路径。

阶段 55 将 README 改为项目入口，客户端操作移至[用户指南](user-guide.md)，本地运行、局域网和日志参数归入[运行与部署](deployment.md#本地与局域网运行)，复验命令由[测试报告](test-report.md#如何运行测试)维护。

阶段 56 将演示摘要并入 README 的「快速开始」。三窗口私聊隔离、重名和停服重连的通过条件见[桌面手动复验](test-report.md#桌面手动复验)，静默失联另有本机心跳专项。

新对话可以直接发送：

> 请在当前 LTalk 仓库继续开发。按 `AGENTS.md` 先读 `docs/STATUS.md`，再读 `README.md`、`docs/DEVLOG.md` 和 `docs/ROADMAP.md` 的相关部分，并核对 Git 状态。最近功能提交为 `2b69ce1`，本机完整回归 152 项通过。当前协议为 v7，SQLite 表结构为 v2。用户反馈新版云端试用暂未发现问题，逐项结果待补记。请按我接下来指定的功能继续。

## 当前功能

| 范围 | 行为与边界 |
| --- | --- |
| 服务端存储 | 首次在新数据目录创建 `ltalk.db`，账号、社交关系、群授权、历史与附件元数据入库，原始附件存于 `attachments/`。文件版数据目录单独保留，新库从空库开始。打包 JAR 声明 SQLite 驱动所需的原生访问权限。 |
| 连接与登录 | 启动页先连接服务器，再注册或登录。TLS、用户名和密码有常驻提示，注册时也说明确认密码要求。预填地址自动连接，注册成功后自动登录。双向心跳检测静默失联。断线后聊天页可按原地址重新连接，预填原用户名并重新验证密码。同址同账号登录恢复所选会话与窗口内草稿。 |
| 启动与发现 | Windows 构建附带 `start-client.cmd`。未指定地址时后台查找约 2 秒，一台自动尝试连接，多台可选择。手动地址优先，「最近连接的服务器」保留 6 个通过握手的地址。命令行服务端默认监听发现 UDP 9001，可关闭。 |
| 公网连接 | TLS 在发送认证数据前校验证书信任链及主机名。公网模式限制接入、未认证连接和认证频率。服务端按连接设置有界发送队列，拥塞时关闭对应连接。提供 Linux 单机服务配置及部署、备份恢复步骤。 |
| 好友与私聊 | 可用昵称或用户名片段查找账号，搜索忽略大小写。好友申请支持离线收取，接收方可接受或拒绝。好友可收离线留言。陌生人聊天需在线发送私聊请求并取得许可，离线时可查看旧记录。可解除好友关系或屏蔽陌生人私聊。 |
| 群组 | 独立群组使用 10 位数字群号，支持创建、搜索、申请、审核、邀请、退群、转让管理员和解散。转让弹窗显示完整群成员名单，可按用户名片段筛选后选择接任者，服务端校验当前权限和成员资格。旧库升级时为已有群组换号并保留其关联记录。实时消息仅向现有成员投递。每次入群授权当时最近 100 条及此后在群期间消息，退群保留已授权记录。 |
| 会话与信息卡片 | 左侧分类分别显示公共会话与在线账号、好友与已加入群聊。点列表空白保持当前会话。搜索框右侧的加号打开「更多操作」，首项是「通知」，其余会话可从「列表外的会话」打开。群聊标题打开群聊信息与名单。点击群消息头像、名单成员或私聊标题打开用户卡片。点击卡片上的群号或用户名可复制到剪贴板。 |
| 发送反馈 | 本人新消息逐条显示发送中、已确认、发送失败或结果待确认。失败或待确认时可取回正文到输入框。客户端等待匹配的服务端回显，默认 5 秒；迟到回显可更新同一条消息。待确认消息保留在当前窗口，重连后可核对本人归档。 |
| 消息撤回 | 右键本人已发出的文本、图片或文件可撤回，服务端校验 60 秒时限并通知有关在线会话。归档在原位置保留提示，搜索跳过该条，后续下载停止授权。已保存的副本仍保留。 |
| 账号切换 | 候选使用独立连接，登录成功后才移交并清理旧界面资料。取消或失败保留原会话。退出登录清除窗口内的历史和草稿，服务端本人归档保留。 |
| 个人信息 | 左下角本人头像 →「个人信息」。能改昵称、上传或重置头像、修改密码。改密码需验证当前密码和确认新密码，成功后本次连接继续，下次使用新密码。 |
| 头像和昵称 | 登录、资料修改和本人历史加载同步展示资料。联系人、私聊标题、气泡和群聊摘要读取当前资料。连续消息组首显示昵称，末尾显示一个头像，滚动到边缘时一起裁切。 |
| 头像规格 | 原图限 12 MiB 和 2400 万像素。短边至少 512 像素则裁剪为 512×512，否则为 256×256，编码上限 128 KiB。旧 48/144 像素头像仍可用，重新上传原图可提高分辨率。 |
| 历史和删除 | 登录显示会话摘要与当前会话最近 20 条，进入其他会话再加载其历史。上滚到顶部或点击按钮加载旧记录，保留阅读位置。公共历史含登录前和离线期间消息。「删除所有聊天记录」的范围是本人的当前会话。 |
| 搜索定位 | 从当前会话搜索本人可见归档，匹配正文、昵称和用户名。定位显示原消息及前后各最多 9 条。查看定位结果或离开最新消息时，输入框右上方显示回到最新消息的箭头。 |
| 未读定位 | 打开会话自动跳到本窗口收到的第一条未读，显示未读分隔。阅读旧消息期间可点击「有 N 条新消息」定位。历史加载不累加实时未读。 |
| 引用回复和复制 | 右键可引用回复或复制完整正文。引用草稿可单独取消，发送后点击引用可定位本人有权查看的原文。 |
| Emoji、图片和附件 | 表情面板提供 69 个彩色 Emoji，正文保存 Unicode。图片按钮接受 PNG、JPEG、GIF 和 BMP，最多 12 MiB 和 2400 万像素。气泡直接显示缩略图，引用为「用户名：[图片]」，原图保存建议使用通用文件名。附件入口可选择任意类型，最多 50 MiB，文件卡片显示图标、名称和大小。确认后开始上传，传输可取消，下载沿用本人历史权限。 |
| 导出 | 从群聊信息卡片或私聊更多菜单打开另存为，选择 JSON/CSV、文件名和位置。JSON 每条一行，含已有的引用编号或附件元数据。CSV 保留原七列。目标文件存在时确认覆盖。Windows 使用原生文件选择器，其他系统使用 Swing 选择器。 |
| 外观与布局 | 浅色、近黑深色、预设或自选纯色/渐变。拖动色盘或输入 HEX 可预览，外观参数单独保存在本机。聊天小于 760 逻辑像素改单列，启动页小于 660 时改为上下排列并可滚动。 |

会话静音和桌面提醒按用户此前的决定暂缓。后续功能由用户指定，见 [路线图](ROADMAP.md)。

## 关键约定

- 上下文边界以 `AGENTS.md` 为准。保持 Java 17 兼容。连接、收发、密码计算、用户图片文件处理和导出在后台，Swing 和会话展示状态在 EDT。
- 服务端和客户端使用同版 JAR。聊天 TCP 使用协议 v7 UTF-8/LF JSON，普通帧 16 KiB，四种头像相关帧 176 KiB，正文最长 2000 个 UTF-16 单元。图片和普通文件通过独立连接传输原始字节。密码为 6～128 个 UTF-16 单元，保留空白。协议没有版本协商。
- 默认监听 `0.0.0.0:9000`。局域网模式最多 20 个连接，TLS 模式默认最多 140 个总连接、24 个未认证连接。初始期限 10 秒，TLS 模式 HELLO 后认证期限默认 60 秒。默认心跳间隔 10 秒、响应期限 20 秒。
- 登录态绑定 TCP。账号保存随机盐与 PBKDF2-HMAC-SHA256 散列，服务端持有数据目录锁。保存结果在 SQLite 事务提交后发布，历史和导出按认证账号隔离。
- 背景保持可选纯色或渐变，浅深色与窄屏均可用。外观只写 `${user.home}/.ltalk/appearance.properties`，项目采用自身的中性描述。
- 最近连接记录单独保存于 `${user.home}/.ltalk/recent-servers.properties`，内容为服务器地址、端口和 TLS 选项。

## 代码入口

| 路径（Java 部分相对 src/main/java/cn/ltalk） | 职责 |
| --- | --- |
| common/Message、MessageCodec、MessageType、Validation | 帧、消息模型和输入校验 |
| common/ConversationScope、UserProfile、AvatarData | 会话键、本人资料、头像格式约束 |
| server/SqliteDatabase、src/main/resources/cn/ltalk/server/sqlite-schema-v1.sql、sqlite-migrate-v2.sql | SQLite 初始化、版本迁移、外键、事务和查询索引 |
| server/AccountStore、ChatArchive、ArchiveView | 账号、改密、展示资料、公共共享归档、本人历史、摘要和搜索 |
| server/SocialStore、common/SocialAction、SocialItem | 好友、私聊许可、请求和群成员的原子保存及协议模型 |
| server/MessageRouter、ClientSession、SessionRegistry、ChatServer | 握手与认证、会话权限、路由、心跳、连接清理 |
| server/AttachmentStore、client/AttachmentChannel、common/Attachment、ChatImage | 一次性传输票据、原文件存储、上传下载、缩略图与图片限制 |
| common/TlsTransport、server/PublicAccess、SessionOutput | TLS 握手和端点校验、公网准入、逐连接发送队列 |
| common/LanDiscovery、server/DiscoveryResponder、client/DiscoveryClient、ServerSelection、RecentServers | UDP 发现帧、后台应答与查找、启动页选择、最近地址保存 |
| client/ChatClient、ClientSessions、HistoryChannel、HistoryExport | 后台连接、候选账号交接、历史分页和导出 |
| client/ChatWindow、HistorySearchDialog、client/ui/WelcomePanel、ConnectionPanel、AuthenticationPanel | 窗口组装、历史浏览与定位、连接及认证界面 |
| client/SocialChannel、SocialDialog、ChatWindow、GroupTransferDialog | 社交请求分页、好友及群组目录、收件箱、卡片的群成员查询与管理员转让弹窗 |
| client/ContactProfile、AvatarImporter、client/ui/ProfileAvatar、ProfilePanel、PasswordChangePanel | 头像导入、展示资料、本人资料编辑及改密 |
| client/ui/ConversationStore、ConversationId、ConversationList、ConversationInfoPanel、MessageTimeline、ResponsiveShell | EDT 展示、会话选择、信息卡片、分组气泡与布局 |
| client/ui/Appearance*、ColorPicker、ChatTheme、ThemePalette、FileDialogs、WindowsFilePicker | 外观与色盘、主题、系统文件对话框 |
| pom.xml、build.ps1、start-client.cmd | Java 17 编译目标、固定 Maven 3.9.9、含依赖打包与 Windows 启动 |

## 云端测试交接

- 2026-11-19，用户在收到新版换版步骤后反馈已在云服务器运行测试，暂未发现问题。本轮提供的 JAR 对应 `2b69ce1`（v7、SQLite 表结构 v2）。服务器实际 JAR 校验值、连接设备数和逐项结果待补记。[新版云端试用](test-report.md#新版云端试用)
- 2026-11-18，用户在 Oracle Cloud 将文件版切换到 `f89dc23`（协议 v6、SQLite 表结构 v1）。截图显示 TLS 服务监听 TCP 9000，已创建 `/var/lib/ltalk/ltalk.db`。用户报告局域网试用及双设备公网基本试用正常。[云端验证范围](test-report.md#sqlite-云端部署与双设备验证)
- 公网服务由 `ltalk.service` 管理，使用 `/opt/ltalk/ltalk-1.0.0.jar`、`/var/lib/ltalk` 和 `/etc/ltalk/server.p12`。关停后复测可核对进程状态、来源 IP、Ubuntu 防火墙规则和证书有效期。更换版本时让服务端和测试客户端使用同版 JAR。[部署手册](deployment.md#更新-jar-与复测)
- 云端旧群换号、重启恢复、双设备撤回和管理员转让待专项记录。长期证书续签和实际云规格的百人容量待验收。[部署手册复测清单](deployment.md#更新-jar-与复测)

## 已有验证与后续核对

- 启动页说明于 2026-11-19 完整离线打包，**152 项通过，0 失败、错误或跳过**。专项覆盖登录与注册的提示、最近连接列表、确认密码和窄屏滚动。日志 `.tools/welcome-guidance-package.log`。[阶段 51](DEVLOG.md#阶段-51-启动页填写说明2026-11-19本轮记录)
- 管理员转让与会话文案于 2026-11-18 完整离线打包，**152 项通过，0 失败、错误或跳过**。窗口回归覆盖多页名单、用户名搜索、退群后清除选择、取消操作和离线成员接任。日志 `.tools/group-transfer-package.log`。[测试报告](test-report.md#近期桌面交互回归)
- 信息卡片复制于 2026-11-18 完整离线打包，**151 项通过，0 失败、错误或跳过**。窗口回归核对原始群号和用户名、切换卡片、公共聊天室和剪贴板失败提示，检查了浅色与窄屏深色预览。日志 `.tools/info-copy-verified.log`。
- 弹窗图标调整于 2026-11-18 完整离线打包，**151 项通过，0 失败、错误或跳过**。窗口回归覆盖图片、文件及建群弹窗的无图标样式和确认、取消流程。日志 `.tools/dialog-icons-package.log`。
- 聊天体验改进于 2026-11-18 完整离线打包，**151 项通过，0 失败、错误或跳过**。覆盖 60 秒边界、撤回授权与持久化、旧群关联迁移、菜单、空白点击及图片和文件。Windows 打包 JAR 在隔离目录启动并重启后保留撤回结果。日志 `.tools/chat-details-verified.log`。[测试报告](test-report.md#聊天体验改进2026-11-18阶段-44)
- SQLite 启动简化于 2026-11-18 完整离线打包，**147 项通过，0 失败、错误或跳过**，约 2:21。打包 JAR 在独立目录用短命令恢复账号与历史，并在 Java 25 严格原生访问模式下成功启动。日志 `.tools/sqlite-startup-final.log`。[测试报告](test-report.md#sqlite-启动简化2026-11-18阶段-42)
- SQLite 服务端存储于 2026-11-18 完整离线打包，**146 项通过，0 失败、错误或跳过**，约 2:18。验证新库和版本检查、事务回滚、权限及备份恢复，并使用打包 JAR 在独立目录验证注册、发消息和进程重启后恢复。日志 `.tools/sqlite-final.log`。独立 Java 17 回归待验证。[测试报告](test-report.md#sqlite-存储2026-11-18阶段-41)
- 信息卡片和会话导航于 2026-11-18 执行 `build.ps1 -o package`，**143 项通过，0 失败、错误或跳过**。验证覆盖群成员分页和权限、上下线与退群刷新、好友申请和删除、会话分类、旧记录入口、回到最新消息箭头，以及宽屏浅色与窄屏深色预览。日志 `.tools/conversation-details-package.log`。本阶段真实高 DPI 和跨设备、云端操作待复测。[测试报告](test-report.md)
- 发送确认及用户搜索于 2026-11-18 执行 `build.ps1 -o package`，**141 项通过，0 失败、错误或跳过**。图片与文件均验证选择后等待确认、取消后无附件消息、明确发送后完成上传。日志 `.tools/user-search-upload-confirm.log`。[测试报告](test-report.md)
- 用户搜索于 2026-11-18 完成本机专项 **4 项通过**，覆盖昵称和用户名片段、24 项分页及从结果发送好友申请。日志 `.tools/user-search.log`。[测试报告](test-report.md)
- 末尾 Emoji 和会话列表显示于 2026-11-17 执行 `build.ps1 -o package`，**139 项通过，0 失败、错误或跳过**。新增回归覆盖末尾首次绘制、追加再删除、纯 Emoji 消息、会话摘要和未读徽标空间。代码变更限客户端显示，协议和数据格式沿用 v6。日志 `.tools/emoji-tail-verified.log`。[测试报告](test-report.md)
- 彩色 Emoji 和输入栏对齐于 2026-11-17 执行 `build.ps1 -o package`，**137 项通过，0 失败、错误或跳过**。覆盖彩色字形、整字符编辑、Unicode 剪贴板、窄屏对齐和混排收发。日志 `.tools/emoji-verified.log`。[测试报告](test-report.md)
- Emoji、图片与附件于 2026-11-17 执行 `build.ps1 -o package`，**135 项通过，0 失败、错误或跳过**。覆盖窄屏 Emoji 收发、分开的图片和附件入口、图片校验、文件原字节、TLS 传输、取消、重启、退群和删除权限。日志 `.tools/attachments-final.log`。新增原生文件对话框的实际点选及多设备传输待现场复测。[测试报告](test-report.md)
- 未读定位和引用回复于 2026-11-17 执行 `build.ps1 -o package`，**128 项通过，0 失败、错误或跳过**。覆盖窄屏首条未读定位、复制完整正文、引用草稿、旧历史定位、重启与权限隔离。日志 `.tools/unread-reply-verified.log`。[测试报告](test-report.md)
- 好友、陌生人私聊与群组于 2026-11-17 执行 `build.ps1 -o package`，**125 项通过，0 失败、错误或跳过**，约 2:04。覆盖离线申请与留言、群实时隔离、入群 100 条、退群历史、管理员移交、解散与重启。验证使用本机回环和 Swing，日志 `.tools/social-verified.log`。[测试报告](test-report.md)
- 按需加载历史与搜索定位于 2026-11-17 执行 `build.ps1 -o package`，**122 项通过，0 失败、错误或跳过**。覆盖回环分页、旧归档、搜索权限、窄屏滚动锚点和定位。构建产物为 `target/ltalk-1.0.0.jar`，日志 `.tools/history-view-verified.log`。[测试报告](test-report.md#按需加载历史与搜索定位2026-11-17阶段-30)
- 公网阶段于 2026-11-17 完整回归 **120 项通过**，约 1:58。本机 100 个 TLS 客户端连续验证约 180 秒，持续阶段 88,100 次群聊投递、无意外断线。已验证慢端关闭、TLS 证书及主机名拒绝、连接及认证限制、备份复制后恢复、打包 JAR 启动。[测试报告](test-report.md)
- Windows 启动与发现于 2026-11-16 执行 `build.ps1 -o package`，**116 项通过，0 失败、错误或跳过**，约 1:28。验证包括本机网卡 IPv4 广播与回环去重、打包启动器、关闭发现后的 TCP 握手、窄屏深色启动页。日志为 `.tools/lan-discovery-repeat.log`。跨设备与防火墙配置仍待现场验收。
- 公共历史补齐于 2026-11-16 执行 `build.ps1 -o package`，**109 项通过，0 失败、错误或跳过**，约 1:29。覆盖晚登录、离线期间公共消息、重启、旧归档、个人删除和私聊隔离。日志为 `.tools/public-history-final.log`，已更新 `target/ltalk-1.0.0.jar`。使用本机回环、隔离数据与 Swing 组件验证。
- 发送反馈和重连于 2026-11-16 执行 `powershell -NoProfile -File .\build.ps1 -o package`，**107 项通过，0 失败、错误或跳过**，约 1:25。Windows、JDK 25.0.4、Maven 3.9.9，以 release 17 编译。验证使用回环 TCP、临时数据与 Swing 组件。构建产物为 `target/ltalk-1.0.0.jar`，本机日志为 `.tools/chat-experience-final.log`。
- 此前功能提交包括 `74aa291` 改密、`d629d5e` 高清头像、`c4840f3` 资料同步、`984cb5b` 消息组头像裁切。各阶段的结果与历史人工反馈见 [测试报告](test-report.md)。
- 当前 v7 的多设备资料同步、独立 JDK 17 自动化回归、各档 DPI、其他客户端系统和长时间负载仍待专项验收。早期宿主机与虚拟机的局域网反馈见对应版本的测试记录。
- 私聊消息和双方可见记录在同一事务提交。按会话翻页和定位使用 SQLite 索引，关键词搜索仍可能扫描较长范围。停服后备份包含 `ltalk.db`、存在的 SQLite 日志和 `attachments/` 的完整目录。附件随历史保留，默认总配额 1 GiB。改密、资料、历史删除、社交操作或上传确认超时可能已生效，可刷新或重登核对。
- 云部署或多设备复测时同步更新两端 JAR，复测昵称查找、图片和文件的发送确认，以及独立群成员名单与会话分类。首屏历史、离线留言、群历史权限、首条未读与引用定位继续沿用部署手册中的回归步骤。[部署手册](deployment.md#更新-jar-与复测)
