# LTalk 实现与评测案例

案例从项目规划、MVP 和后续迭代中提炼。数字、类名和协议属于 LTalk。接手 LTalk 时先读 `docs/STATUS.md` 和 `README.md`，再核对源码与本轮分支。其他项目可复用下面的规格写法、夹具和证据口径。

## 定位入口与实现边界

案例以功能基线 `2b69ce1` 为参照。LTalk 使用 Java 17 编译目标、Maven、Swing、Gson 和 SQLite JDBC，聊天协议为 TCP v7，SQLite 表结构 v2。聊天按 UTF-8 编码的 LF 分隔 JSON 帧收发。局域网发现用 UDP，公网连接可启用 TLS，文件与原图在独立连接上传输原始字节。

| 查看任务 | 代码入口与改动时要保持的约束 |
| --- | --- |
| 建连、会话与慢端 | `server.ChatServer` 接入连接，`ClientSession` 持有 Socket 和心跳，`SessionRegistry` 管理名称预留、就绪和移除，`SessionOutput` 串行发送并限制积压。业务投递只面向就绪会话，`LOGIN_OK` 先于业务事件；慢连接的清理应局限于该连接。 |
| 帧和身份 | `common.MessageCodec`、`MessageType`、`Validation` 定义编码和校验。普通帧上限 16 KiB，指定头像帧 176 KiB，正文上限 2000 个 UTF-16 单元。身份绑定服务端认证连接；`requestId` 关联响应，`messageId` 由服务端生成。`common.Heartbeat` 校验当前探测的 PONG 编号。 |
| 消息、权限和历史 | `server.MessageRouter` 处理聊天、历史和社交请求，`SocialStore` 管理好友、私聊许可和群成员，`AccountStore` 管理账号。公共消息发给就绪会话；独立群发给就绪的当前成员；好友可离线留言，陌生人须获私聊许可且在线。`ChatArchive` 和 `SqliteDatabase` 承担授权归档和事务，私聊正文与双方可见记录一起提交。 |
| 附件和撤回 | `client.AttachmentChannel` 与 `server.AttachmentStore` 管理独立传输及票据。附件下载由本人历史可见范围授权。撤回由服务端核对发送者和 60 秒时限，归档原位置保留撤回提示；后续搜索跳过该条，停止授权新下载。 |
| 桌面与恢复 | `client.ChatClient`、`ClientSessions` 管理后台网络和会话交接，`ChatWindow` 负责窗口交互，`client.ui.ConversationStore` 保存展示状态。Swing 更新在 EDT。手动重连同一服务器并重新登录同一账号后，可恢复窗口内所选会话与草稿。发送确认由匹配的服务端回显驱动。 |

## 评测集

编号用于将意图、实现和测试对应起来。选取与本轮任务有关的案例，按目标版本核对规格。

### 连接、路由和账号

| 编号与意图 | 前置、操作及通过条件 | 复验入口 |
| --- | --- | --- |
| NET-01 多人收发且私聊隔离 | A、B、C 均登录，A 可私聊 B。A 向公共会话发送唯一正文，三端各收一条。随后向 B 私聊，A 收到本人回显、B 收到正确发送者与正文，C 收不到该私聊。另验证同名并发登录只有一方成功，获准连接先收到 `LOGIN_OK`，随后可接收业务事件。 | `ChatServerTest`、`MessageCodecTest` |
| NET-02 空闲静默失联可恢复 | A 经过回环代理登录，B 直连。连接仍打开时分别停止单向转发。测试配置为 80 ms 探测间隔、500 ms 响应超时，在 2500 ms 容差上限内 A 断开，服务端释放名额并通知 B。A 用原账号重新登录并发消息，B 保持在线。正常空闲保持在线，错误编号的 PONG 或其他流量无法推迟超时。 | `HeartbeatIntegrationTest` |
| NET-03 发现服务且手动地址优先 | 模拟无候选、一台和多台服务端。单台可自动连接并进入认证流程，多台等用户选择。输入手动地址或取消后，迟到的发现结果不得触发连接。发现 UDP 端口被占用时，手动 TCP 连接仍可用。 | `DiscoveryIntegrationTest`、`ChatWindowTest` |
| AUTH-01 账号身份与导出范围 | 同名并发注册只有一个成功。认证前请求业务返回错误。认证后从当前会话导出，仅含本人可见及该会话范围内的记录，跨会话不能沿用游标；写出失败或取消后检查输出清理。 | `AuthenticationExportTest`、`HistoryPersistenceTest` |
| CHAT-01 发送结果与断线恢复 | 让 A 发送后等过确认期限，界面显示结果待确认。迟到的本人匹配回显更新同一气泡。切断连接后保留窗口内草稿；同址同账号重连可恢复当前会话。切换账号时，候选失败或取消仍保留旧会话，成功才交接。 | `ChatWindowTest#sendFeedbackAndReconnectKeepConversationAndDrafts`、`ChatWindowTest#accountSwitchKeepsOldChatThroughFailureAndCancelAndOnlyClearsItAfterSuccess` |

### 群、历史和桌面交互

| 编号与意图 | 前置、操作及通过条件 | 复验入口 |
| --- | --- | --- |
| GROUP-01 授权覆盖每次入群的历史窗口 | A 为需审核群的管理员，B 未入群，A 先发 104 条编号消息。B 入群前及申请被拒后均无权限。获准后导出恰为第 5 至 104 条，引用第 1 条被拒绝。退群后，B 仍可导出已有记录，且收不到或定位不到退群后发表的新消息。重入群时按新的窗口重新授予最近记录。 | `SocialIntegrationTest#groupsEnforceReviewInvitationRolesAndHistoryWindowsAcrossRestart` |
| HIST-01 本人删除及跨页定位 | A、B 共有私聊。A 删除本人副本并重登，历史与导出均无该条，B 仍可查询。另在可见会话中翻找尚未加载的消息，核对搜索或引用跳转到匹配的原消息，越权或跨会话引用被拒。 | `HistoryPersistenceTest`、`ChatWindowTest#olderHistoryKeepsTheViewportAndSearchLocatesAnUnloadedMessageWithContext` |
| UI-01 窄屏打开会话定位首条未读 | A 在另一个会话时收到新消息。用窄屏导航打开目标会话，等待历史加载后核对首条未读在视口内。引用一条消息时可以取消预览，已发送的引用可定位尚未加载的原文。 | `ChatWindowTest#openingNarrowConversationJumpsToFirstUnreadAndQuotedReplyCanLocateUnloadedOriginal` |
| UI-02 末尾 Emoji 首次绘制 | 输入「中😀」与纯 Emoji，在首次绘制时检查尾部图形，再追加普通字符并删除。对照气泡与会话摘要，核对收发和复制仍保留 Unicode 字符。 | `EmojiTextTest`、`ConversationListTest` |
| FILE-01 确认后才传输且遵守历史授权 | 分别从图片和附件入口选择文件，确认前或取消后均无附件消息，确认后传输。对照原字节，并在重启、退群和个人删除后检查是否允许下载。另验证取消或超限上传不会发布消息，也不会阻塞聊天。 | `ChatWindowTest#imageAndFileUploadsRequireExplicitConfirmation`、`AttachmentTransferTest` |

### 数据与部署

| 编号与意图 | 前置、操作及通过条件 | 复验入口 |
| --- | --- | --- |
| DATA-01 切换存储与事务回滚 | 初次从文件版切到 SQLite 时用新目录建空库，旧目录原样保留。独立 SQLite 夹具中注入私聊可见记录写入失败，核对正文和双方可见性一起回滚。重试成功后库中有一条正文和两条可见记录，两方收到同一消息编号。 | `SqlitePersistenceTest#refusesLegacyDirectoryAndCanStartFromAnIndependentEmptyDirectory`、`SqlitePersistenceTest#privateMessageAndBothVisibilityRowsCommitOrRollBackTogether` |
| DATA-02 旧群换号保留关联 | 在 SQLite v1 夹具中加入旧群、成员、待处理申请、消息引用及个人隐藏状态。启动升级后核对唯一的 10 位数字群号与关联权限，检查外键，再打开已升级库核对群号稳定。 | `SqlitePersistenceTest#upgradesUuidGroupsWithTheirMessagesPermissionsAndPendingRequests` |
| MSG-02 撤回与本人删除有各自的边界 | 用服务端时钟验证本人消息满 60 秒仍可撤回，超过 1 纳秒失败。对方和第三方不能撤回。合法撤回通知有关在线会话，原位置和编号保留，搜索跳过，后续附件下载停止授权。重启仍保留撤回状态，已下载副本由用户持有。 | `MessageRecallTest`、`AttachmentTransferTest` |
| DEPLOY-01 TLS 接入与容量 | 证书受信且主机名一致才能开始认证。验证未认证连接名额回收及认证限流。回环用 100 个 TLS 客户端验证 20 条突发消息、10 个重连、停止读取的慢端被单独关闭，正常客户端继续收包。记录持续时长、P95、递送量与意外掉线数。 | `PublicConnectionTest`、`PublicCapacityTest` |

## 规格示例 · 从群成员名单转让管理员

**GROUP-02**。管理员希望在名单里直接挑选接任者。以管理员已打开独立群信息卡片为前提，拆为分页加载、按用户名片段筛选和确认转让三个操作。保留现有 `GROUP_MEMBERS` 和 `GROUP_TRANSFER` 请求。检查 `ChatWindow` 和 `GroupTransferDialog` 的界面流程。由服务端在提交时核对当前管理员和目标仍为成员。

| 用例 | 前置和输入 | 操作与通过条件 |
| --- | --- | --- |
| GROUP-02-01 | 独立群内 25 人，含离线成员 | 等名单加载，核对包含两页成员。按用户名片段找到第二页的成员并可点选。检查名单只匹配本人或没有匹配时均无法提交。 |
| GROUP-02-02 | 已选中某位现有成员 | 让该成员退群，等名单刷新，核对原选择清除且提交停用。选中另一位成员后取消，从群资料核对原管理员仍有管理权限。 |
| GROUP-02-03 | 已选中有效的离线成员 | 确认后从群资料核对管理员身份转移及原管理员失去该操作权限。 |

实现见 `8bc1f94`。复验入口为 `ChatWindowTest#administratorCanBrowseAllRosterPagesSearchAndTransferToAnOfflineMember`。协议的名单分页和服务端权限由 `SocialIntegrationTest` 覆盖。历史证据见 `docs/test-report.md` 的「近期桌面交互回归」。

## 运行与取证

在仓库根目录选择测试类或方法执行。网络用例用回环端口和独立数据目录，带窗口的用例需要图形桌面会话。

```powershell
powershell -NoProfile -File .\build.ps1 -o "-Dtest=HeartbeatIntegrationTest" test
powershell -NoProfile -File .\build.ps1 -o "-Dtest=ChatWindowTest#administratorCanBrowseAllRosterPagesSearchAndTransferToAnOfflineMember" test
powershell -NoProfile -File .\build.ps1 -o package
```

`-o` 依赖已有缓存。也可运行 `mvn "-Dtest=HeartbeatIntegrationTest" test` 和 `mvn package`。结果见 `target/surefire-reports/`。回环容量用例默认持续阶段约 4 秒。要单独采集 3 分钟回环数据，可运行 `mvn "-Dtest=PublicCapacityTest" "-Dltalk.capacity.seconds=180" test` 并记录输出中的 `PUBLIC_CAPACITY`。同机探针和服务端共享机器，用例为共享 IP 放宽了连接和认证限额。

### 怎样表述历史证据

| 开发中的观察与决策 | 可引用的证据及范围 |
| --- | --- |
| 旧版 MVP 中，虚拟机断网后客户端有时要发送才察觉，空闲连接可能一直保留。将它转成独立心跳规格，同时保留正常空闲的对照。 | `docs/test-report.md` 的 Q17、心跳阶段和后续虚拟机反馈分别记录旧限制、本机单向静默自动化以及用户现场反馈。虚拟机反馈未提供精确耗时。 |
| 登录时可见历史逐条出现，先调整初始加载和分页，再决定是否换存储。 | `5585290` 与阶段 30 报告提供按需加载与定位的本机证据。SQLite 切换的动机包含一致性和后续维护，登录观感单独验证。 |
| 内置 Emoji 在行尾只有追加字符后才正确绘制，复现条件包括首次绘制。 | `96e6bd0` 与阶段 36 报告记录纯 Emoji、尾部首次绘制、追加再删除及会话摘要的用例。真实高 DPI 另行验证。 |
| 单机约百人目标拆成容量、慢端隔离与真实外网连通性。 | `0a7ef77` 所在阶段记录 100 个本机 TLS 客户端、180.14 秒、88,100 次投递、P95 11.985 ms。P95 口径是从发送到全部预期探针收到的本机回环耗时。新版公网反馈仅记基本试用。 |
| 从文件版建空库与 SQLite v1 到 v2 的原位升级由不同夹具验收。 | `2f56c09` 与 `51697fa` 对应两个阶段。云端 v7 的用户反馈未提供 JAR 校验值或逐项结果，旧群迁移与云端重启恢复仍需单独取证。 |
