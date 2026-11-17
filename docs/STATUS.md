# LTalk 当前状态与开发交接

更新于 **2026-11-17（Asia/Shanghai）**。工作目录为 `D:\Agent\AllProjects\LTalk`，核对时分支为 `master`，未配置远端。含可选 TLS 公网模式，使用 **Java 17 目标字节码、Swing、Maven、TCP 协议 v6 和 UDP 发现 v1**。接续时先检查实际 Git 状态和提交，再按用户的新需求继续。

## 从哪里接手

1. 阅读 [AGENTS.md](../AGENTS.md) 和本页，再看 [README](../README.md) 的构建与操作。通过 `git status --short` 和 `git log -5 --oneline` 核对现场。
2. 改功能前按需查 [需求与用户流程](requirements.md)、[架构](design.md)、[界面设计](ui-design.md) 和 [协议](protocol.md)，再核对对应代码与测试。
3. [测试报告](test-report.md) 以日期和阶段区分证据，[开发日志](DEVLOG.md) 保留决策与变更详情。根目录的[任务拆解](../局域网聊天项目任务拆解.md)和[早期草稿](devlog-early-draft.md)属于历史资料。
4. `target/`、`.tools/` 和默认 `data/` 为本机构建、证据或数据目录，均不提交。验证使用独立临时数据目录。运行已有账号时，用原数据目录，或通过 `-Dltalk.dataDir` 指定其绝对路径。
5. 选择下一项开发工作时，查阅 [后续开发路线图](ROADMAP.md)。下一项功能由用户指定。云端复测按[更新 JAR 与复测](deployment.md#更新-jar-与复测)操作。

新对话可以直接发送：

> 请继续开发 `D:\Agent\AllProjects\LTalk`。先按 AGENTS.md 阅读 docs/STATUS.md、README.md，以及 docs/DEVLOG.md 和 docs/ROADMAP.md 的相关部分，核对 Git 状态，再按我指定的需求继续。

## 当前功能

| 范围 | 行为与边界 |
| --- | --- |
| 连接与登录 | 启动页先连接服务器，再注册或登录。预填地址自动连接，注册成功后自动登录。双向心跳检测静默失联。断线后聊天页可按原地址重新连接，预填原用户名并重新验证密码。同址同账号登录恢复所选会话与窗口内草稿。 |
| 启动与发现 | Windows 构建附带 `start-client.cmd`。未指定地址时后台查找约 2 秒，一台自动尝试连接，多台可选择。手动地址优先，最近列表保存 6 个通过握手的地址。命令行服务端默认监听发现 UDP 9001，可关闭。 |
| 公网连接 | TLS 在发送认证数据前校验证书信任链及主机名。公网模式限制接入、未认证连接和认证频率。服务端按连接设置有界发送队列，拥塞时关闭对应连接。提供 Linux 单机服务配置及部署、备份恢复步骤。 |
| 好友与私聊 | 按区分大小写的用户名查找账号。好友申请支持离线收取，接收方可接受或拒绝。好友可收离线留言。陌生人聊天需在线发送私聊请求并取得许可，离线时可查看旧记录。可解除好友关系或屏蔽陌生人私聊。 |
| 群组 | 保留公共聊天室，独立群组支持创建、搜索、申请、审核、邀请、退群、转让管理员和解散。实时消息仅向现有成员投递。每次入群授权当时最近 100 条及此后在群期间消息，退群保留已授权记录。 |
| 发送反馈 | 本人新消息逐条显示发送中、已确认、发送失败或结果待确认。失败或待确认时可取回正文到输入框。客户端等待匹配的服务端回显，默认 5 秒；迟到回显可更新同一条消息。待确认消息保留在当前窗口，重连后可核对本人归档。 |
| 账号切换 | 候选使用独立连接，登录成功后才移交并清理旧界面资料。取消或失败保留原会话。退出登录清除窗口内的历史和草稿，服务端本人归档保留。 |
| 个人信息 | 左下角本人头像 →「个人信息」。能改昵称、上传或重置头像、修改密码。改密码需验证当前密码和确认新密码，成功后本次连接继续，下次使用新密码。 |
| 头像和昵称 | 登录、资料修改和本人历史加载同步展示资料。联系人、私聊标题、气泡和群聊摘要读取当前资料。连续消息组首显示昵称，末尾显示一个头像，滚动到边缘时一起裁切。 |
| 头像规格 | 原图限 12 MiB 和 2400 万像素。短边至少 512 像素则裁剪为 512×512，否则为 256×256，编码上限 128 KiB。旧 48/144 像素头像仍可用，重新上传原图可提高分辨率。 |
| 历史和删除 | 登录显示会话摘要与当前会话最近 20 条，进入其他会话再加载其历史。上滚到顶部或点击按钮加载旧记录，保留阅读位置。公共历史含登录前和离线期间消息，旧版归档沿用原有账号权限，删除只影响本人。 |
| 搜索定位 | 从当前会话搜索本人可见归档，匹配正文、昵称和用户名。定位显示原消息及前后各最多 9 条，标记原消息并支持返回最近消息。 |
| 未读定位 | 打开会话自动跳到本窗口收到的第一条未读，显示未读分隔。阅读旧消息期间可点击「有 N 条新消息」定位。 |
| 引用回复和复制 | 右键可引用回复或复制完整正文。引用草稿可单独取消，发送后点击引用可定位本人有权查看的原文。 |
| 导出 | 当前会话菜单打开另存为，选择 JSON/CSV、文件名和位置。JSON 为合法数组，每条一行。目标文件存在时确认覆盖。Windows 使用原生文件选择器，其他系统使用 Swing 选择器。 |
| 外观与布局 | 浅色、近黑深色、预设或自选纯色/渐变。拖动色盘或输入 HEX 可预览，外观参数单独保存在本机。聊天小于 760 逻辑像素改单列，启动页小于 660 时改为上下排列并可滚动。 |

附件与表情面板入口为占位。自动重连、已读回执、聊天文件传输和找回密码仍在后续范围。会话列表展示群组、好友、在线用户和已有会话。

## 关键约定

- 上下文边界以 `AGENTS.md` 为准。保持 Java 17 兼容。连接、收发、密码计算、用户图片文件处理和导出在后台，Swing 和会话展示状态在 EDT。
- 服务端和客户端一起升级到协议 v6。TCP 使用 UTF-8/LF JSON，普通帧 16 KiB，四种头像相关帧 176 KiB，正文最长 2000 个 UTF-16 单元。密码为 6～128 个 UTF-16 单元，保留空白。协议没有版本协商。
- 默认监听 `0.0.0.0:9000`。局域网模式最多 20 个连接，TLS 模式默认最多 140 个总连接、24 个未认证连接。初始期限 10 秒，TLS 模式 HELLO 后认证期限默认 60 秒。默认心跳间隔 10 秒、响应期限 20 秒。
- 登录态绑定 TCP。账号保存随机盐与 PBKDF2-HMAC-SHA256 散列，服务端持有数据目录锁。资料与改密先原子写文件再发布结果，历史和导出按认证账号隔离。
- 背景保持可选纯色或渐变，浅深色与窄屏均可用。外观只写 `${user.home}/.ltalk/appearance.properties`，项目采用自身的中性描述。
- 最近服务器单独保存于 `${user.home}/.ltalk/recent-servers.properties`，内容为服务器地址、端口和 TLS 选项。

## 代码入口

| 路径（Java 部分相对 src/main/java/cn/ltalk） | 职责 |
| --- | --- |
| common/Message、MessageCodec、MessageType、Validation | 帧、消息模型和输入校验 |
| common/ConversationScope、UserProfile、AvatarData | 会话键、本人资料、头像格式约束 |
| server/AccountStore、ChatArchive、ArchiveView | 账号、改密、展示资料、公共共享归档、本人历史、摘要和搜索 |
| server/SocialStore、common/SocialAction、SocialItem | 好友、私聊许可、请求和群成员的原子保存及协议模型 |
| server/MessageRouter、ClientSession、SessionRegistry、ChatServer | 握手与认证、会话权限、路由、心跳、连接清理 |
| common/TlsTransport、server/PublicAccess、SessionOutput | TLS 握手和端点校验、公网准入、逐连接发送队列 |
| common/LanDiscovery、server/DiscoveryResponder、client/DiscoveryClient、ServerSelection、RecentServers | UDP 发现帧、后台应答与查找、启动页选择、最近地址保存 |
| client/ChatClient、ClientSessions、HistoryChannel、HistoryExport | 后台连接、候选账号交接、历史分页和导出 |
| client/ChatWindow、HistorySearchDialog、client/ui/WelcomePanel、ConnectionPanel、AuthenticationPanel | 窗口组装、历史浏览与定位、连接及认证界面 |
| client/SocialChannel、SocialDialog | 社交请求、好友及群组目录和收件箱 |
| client/ContactProfile、AvatarImporter、client/ui/ProfileAvatar、ProfilePanel、PasswordChangePanel | 头像导入、展示资料、本人资料编辑及改密 |
| client/ui/ConversationStore、ConversationId、ConversationList、MessageTimeline、ResponsiveShell | EDT 展示、会话选择、分组气泡与布局 |
| client/ui/Appearance*、ColorPicker、ChatTheme、ThemePalette、FileDialogs、WindowsFilePicker | 外观与色盘、主题、系统文件对话框 |
| pom.xml、build.ps1、start-client.cmd | Java 17 编译目标、固定 Maven 3.9.9、含依赖打包与 Windows 启动 |

## 云端测试交接

- 用户已在 Oracle Cloud 的 Ubuntu 24.04 实例完成 Tailscale 私网和公网 TLS 基本验收。Windows 宿主机和虚拟机均可连接、登录及收发消息。具体证据边界见 [测试报告](test-report.md#oracle-cloud-私网与公网验收)。
- 公网服务由 `ltalk.service` 管理，使用 `/opt/ltalk/ltalk-1.0.0.jar`、`/var/lib/ltalk` 和 `/etc/ltalk/server.p12`。用户计划停掉服务并保留云端 `ltalk-public-test` 的 TCP 9000 测试入站规则。复测先核对进程状态、来源 IP、Ubuntu 临时防火墙规则和证书有效期。更换版本时让服务端和测试客户端使用同版 JAR。[部署手册](deployment.md#更新-jar-与复测)
- 不同物理设备和独立接入网络、长期证书续签、实际云规格的百人容量仍待验收。当前反馈足以接续新功能开发。

## 已有验证与后续核对

- 未读定位和引用回复于 2026-11-17 执行 `build.ps1 -o package`，**128 项通过，0 失败、错误或跳过**。覆盖窄屏首条未读定位、复制完整正文、引用草稿、旧历史定位、重启与权限隔离。日志 `.tools/unread-reply-verified.log`。[测试报告](test-report.md)
- 好友、陌生人私聊与群组于 2026-11-17 执行 `build.ps1 -o package`，**125 项通过，0 失败、错误或跳过**，约 2:04。覆盖离线申请与留言、群实时隔离、入群 100 条、退群历史、管理员移交、解散与重启。验证使用本机回环和 Swing，日志 `.tools/social-verified.log`。[测试报告](test-report.md)
- 按需加载历史与搜索定位于 2026-11-17 执行 `build.ps1 -o package`，**122 项通过，0 失败、错误或跳过**。覆盖回环分页、旧归档、搜索权限、窄屏滚动锚点和定位。构建产物为 `target/ltalk-1.0.0.jar`，日志 `.tools/history-view-verified.log`。[测试报告](test-report.md#按需加载历史与搜索定位2026-11-17阶段-30)
- 公网阶段于 2026-11-17 完整回归 **120 项通过**，约 1:58。本机 100 个 TLS 客户端连续验证约 180 秒，持续阶段 88,100 次群聊投递、无意外断线。已验证慢端关闭、TLS 证书及主机名拒绝、连接及认证限制、备份复制后恢复、打包 JAR 启动。[测试报告](test-report.md)
- Windows 启动与发现于 2026-11-16 执行 `build.ps1 -o package`，**116 项通过，0 失败、错误或跳过**，约 1:28。验证包括本机网卡 IPv4 广播与回环去重、打包启动器、关闭发现后的 TCP 握手、窄屏深色启动页。日志为 `.tools/lan-discovery-repeat.log`。跨设备与防火墙配置仍待现场验收。
- 公共历史补齐于 2026-11-16 执行 `build.ps1 -o package`，**109 项通过，0 失败、错误或跳过**，约 1:29。覆盖晚登录、离线期间公共消息、重启、旧归档、个人删除和私聊隔离。日志为 `.tools/public-history-final.log`，已更新 `target/ltalk-1.0.0.jar`。使用本机回环、隔离数据与 Swing 组件验证。
- 发送反馈和重连于 2026-11-16 执行 `powershell -NoProfile -File .\build.ps1 -o package`，**107 项通过，0 失败、错误或跳过**，约 1:25。Windows、JDK 25.0.4、Maven 3.9.9，以 release 17 编译。验证使用回环 TCP、临时数据与 Swing 组件。构建产物为 `target/ltalk-1.0.0.jar`，本机日志为 `.tools/chat-experience-final.log`。
- 此前功能提交包括 `74aa291` 改密、`d629d5e` 高清头像、`c4840f3` 资料同步、`984cb5b` 消息组头像裁切。各阶段的结果与历史人工反馈见 [测试报告](test-report.md)。
- 当前 v6 的多设备资料同步等完整流程、独立 JDK 17 自动化回归、各档 DPI、其他客户端系统和长时间负载仍待验收。早期宿主机＋虚拟机的局域网反馈保持原版本归属。
- 跨账号私聊归档写入没有事务保证。JSONL 深页和搜索会扫描归档，社交数据每次修改原子替换 `social.json`。备份须包含该文件及群组日志。改密、资料、历史删除或社交操作超时可能已生效，可刷新或重登核对。
- 云部署或多设备复测时同步更新两端 JAR，重点核对离线好友留言、请求刷新及群组历史边界。其他现场验证与后续开发选项见 [路线图](ROADMAP.md)。
