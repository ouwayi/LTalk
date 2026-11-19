# 当前需求与操作流程

基线为根目录的[课程任务拆解](../局域网聊天项目任务拆解.md)。原课程目标为单服务端、单公共聊天室和 5～10 个桌面客户端。当前版本还支持独立群组、好友离线留言、会话分类与信息卡片、按需浏览和搜索历史、未读定位与引用回复、Emoji 与附件，以及可选的单机公网 TLS 模式。功能总览与验证范围见 [STATUS](STATUS.md)。

| 需求 | 实现与约束 | 验证入口 |
| --- | --- | --- |
| R-01 配置并启动 | CLI 端口/IP/连接上限，默认 9000/0.0.0.0/20 | ChatServerTest.occupiedPortAndShutdownAreObservable |
| R-02 账号注册登录 | 昵称/用户名/密码，或随机用户名；密码验证后加入。用户名 strip 后 `[A-Za-z0-9_]{1,20}`；重名保留原会话；LOGIN_OK 最先返回 | duplicateLogin / concurrentSameName 测试 |
| 启动页填写说明 | TLS 由用户选择，旁边常驻公网直连与局域网或 VPN 的说明。登录和注册显示用户名与密码填写规则，注册另有确认密码提示。可从「最近连接的服务器」重用地址和 TLS 选项 | ChatWindowTest、ThemeRenderingTest |
| R-03 在线列表 | 上下线推送全量快照，版本递增，重连重置本地版本。公共聊天室分类显示公共会话与其他在线账号 | ChatServerTest、ChatWindowTest |
| R-04 群聊 | 公共消息发给所有就绪账号，独立群消息只发给就绪的当前成员，均含发送者回显。公共归档可查登录前和离线期间的共享记录 | ChatServerTest、HistoryPersistenceTest、SocialIntegrationTest |
| R-05 私聊 | 已有好友关系，或已获私聊许可且对方在线时允许发送。在线投递只涉及发送者和目标，离线好友从本人历史查阅 | ChatServerTest、SocialIntegrationTest |
| R-06 消息展示 | 显示正文、图片缩略图或文件卡片，以及当地时间和当前会话标识。接收气泡按发送者连续分组，显示同步昵称和头像 | ChatWindowTest、MessageTimelineTest |
| R-07 边界与协议 | 2000 UTF-16 单元正文、普通帧 16 KiB、指定头像帧 176 KiB，换行转义，错误按连接处理 | MessageCodecTest、ChatServerTest |
| R-08 清理与手动重连 | EOF/RST/读写失败清理；服务端停止关闭会话；旧连接不能影响新连接 | ChatServerTest / ChatClientTest |
| R-09 桌面响应 | 后台连接/收发、EDT 更新、连接中禁重复提交、断线禁发送 | ChatClientTest + Swing 人工验收 |
| R-10 目标离线保护 | 好友可留离线消息。陌生人离线时只可查看旧记录与草稿，重新上线后重选会话 | ConversationStoreTest、ChatWindowTest、SocialIntegrationTest |
| R-11 资源限制 | 局域网模式默认 20 连接；TLS 模式默认 140 个总连接、24 个未认证连接和单独的认证期限。初始握手 10 秒，HELLO 后心跳保活，逐连接有界发送队列 | ChatServerTest、LoadTest、PublicCapacityTest |
| R-12 局域网交付 | 跨系统群聊、私聊和上下线验收，说明环境与证据来源 | [验收记录](test-report.md)中的 Q16 和[SQLite 版本试用反馈](test-report.md#sqlite-云端部署与双设备验证) |
| X1 心跳与失联 | HELLO 或直接登录成功后双向 PING/PONG，间隔/响应期限可配置，正常空闲不踢，静默失联清理并允许手动重连 | HeartbeatIntegrationTest / HeartbeatSettingsTest 本机通过；2026-10-28 用户反馈虚拟机断网后自动检测断开正常，实际测试日期/配置/精确耗时未提供 |
| X2 当前会话导出 | 从群聊信息卡片或私聊更多菜单选择 JSON/CSV、文件名和位置，覆盖旧文件需确认 | AuthenticationExportTest、HistoryExportTest、FileDialogsTest |
| X3 账号与资料 | 注册登录、资料编辑与同步、本人改密。改密成功后当前会话继续，下次用新密码登录 | AccountFlowTest、ProfileConnectionTest、PasswordChangeTest、ChatWindowTest |
| X4 授权历史 | 服务端重启后保留本人可见记录，登录恢复会话摘要及当前会话最近一页，上滚取旧记录，搜索可定位到原消息。支持逐条删除和「删除所有聊天记录」，范围均为本人的当前会话 | HistoryPersistenceTest、HistoryClientTest、HistoryStoreTest、ChatWindowTest |
| SQLite 存储 | 首启创建数据库，服务端事务保存账号、社交关系、群成员权限、消息及附件元数据，文件版切换使用独立空库 | SqlitePersistenceTest、HistoryPersistenceTest、SocialIntegrationTest、PublicConnectionTest |
| X5 私聊离线投递 | 已互为好友时可发送离线消息，接收人通过本人历史查阅 | SocialIntegrationTest、ChatWindowTest |
| X6 聊天文件传输 | 图片专用入口接受 PNG、JPEG、GIF 与 BMP，最多 12 MiB 和 2400 万像素。普通附件支持任意类型，最多 50 MiB。选中文件后确认目标才上传，支持取消和保存，下载按本人历史权限授权 | AttachmentTransferTest、AttachmentIntegrationTest、ChatWindowTest、FileDialogsTest |
| 好友与独立群组 | 按昵称或用户名片段查找，双方确认好友关系，支持独立私聊许可、加群审核与邀请、管理员移交或解散。管理员从完整名单点选或搜索接任者，服务端核对资格。退群后保留已授权的历史 | SocialIntegrationTest、ChatWindowTest |
| 会话导航与资料 | 「好友与群聊」显示好友与已加入的独立群，其余会话可从「列表外的会话」打开。点列表空白保持当前会话。点击标题、群消息头像或成员可打开对应信息卡片，从卡片可复制群号或用户名。离开最新消息或查看定位结果时显示返回箭头 | ChatWindowTest |
| 消息撤回 | 本人发出的消息 60 秒内可撤回。原位置显示提示，后续搜索和下载沿用撤回状态 | MessageRecallTest、AttachmentTransferTest、ChatWindowTest |
| 未读定位与引用 | 进入会话定位本窗口的首条未读。右键可复制完整正文或引用，点击引用按本人历史权限定位 | ChatWindowTest、ConversationStoreTest、MessageTimelineTest、HistoryPersistenceTest |

## 用户流程

```mermaid
stateDiagram-v2
    [*] --> DISCONNECTED
    DISCONNECTED --> CONNECTING: 输入地址和端口连接（预填地址自动发起）
    CONNECTING --> SERVER_CONNECTED: HELLO_OK，仅服务器连接
    CONNECTING --> DISCONNECTED: 失败、超时、取消
    SERVER_CONNECTED --> AUTHENTICATING: 登录或注册
    AUTHENTICATING --> AUTHENTICATING: REGISTER_OK 后自动 LOGIN
    AUTHENTICATING --> SERVER_CONNECTED: 错密码、重名等可重试错误
    AUTHENTICATING --> CONNECTED: LOGIN_OK
    AUTHENTICATING --> DISCONNECTED: 认证超时或网络错误
    CONNECTED --> CONNECTED: 加载历史、群聊、私聊、本人资料和改密、会话导出
    CONNECTED --> CONNECTING: 明确退出登录，清除身份后重建未认证连接
    CONNECTED --> DISCONNECTED: EOF、网络错误、心跳超时
    SERVER_CONNECTED --> DISCONNECTED: 断开或心跳超时
    DISCONNECTED --> CONNECTING: 用户手动重连
```

- 加入失败：聊天功能保持禁用，错误原因可见，修正名称/地址后重试。
- 群聊：确认目标为群聊 → 输入 → 提交 → 等待服务端回显，不在按钮处理时添加正式聊天消息。
- 私聊选择目标后查看标题状态。好友离线时可留言。陌生人需在线接受请求，获许可后重新上线仍需重选会话。
- 发送图片或文件时，先选择文件，再核对确认窗口里的文件名和目标会话。确认后可查看进度或取消传输。接收方可在本人可见的消息气泡中保存附件。
- 故障或普通断开后，保留本窗口记录和草稿，禁用发送。重新登录会重新加载摘要和所选会话最近一页。明确退出登录清理窗口身份、历史、草稿和密码。切换账号在独立连接验证成功后才交接，失败或返回时保留原会话。
- 关闭窗口：关闭 Socket、发送任务、登录及心跳计时器；客户端后台线程设为 daemon，但仍显式清理。

## 验收条件

自动化测试用隔离数据目录和本机回环连接，通过第三方 Socket 的收包验证私聊隔离，并用 10 个客户端进行课程规模短负载测试。SQLite 回归验证空库初始化、事务失败回滚、按本人权限读取，以及用同一目录重启后恢复账号和聊天历史。

当前 v7 的逐项验证来自本机回归。用户报告本轮云端试用暂未发现问题，具体功能和设备范围待补记。早期 SQLite 局域网试用与双设备公网基本试用对应 `f89dc23`（v6）。[新版云端试用](test-report.md#新版云端试用) · [旧版双设备验证](test-report.md#sqlite-云端部署与双设备验证)

## 账号与导出验收（2026-10-29，历史阶段）

X3：服务端必填/格式/长度校验、随机及手填唯一用户名、并发重名唯一成功、加盐哈希落盘及重启登录、错误密码/未知用户统一 LOGIN_FAILED、所有身份功能未登录统一 NOT_AUTHENTICATED、登出/断线失效。无跨连接 token，沿用 TCP 会话。

X2：仅导出本人归档，其他账号/伪造游标 FORBIDDEN；空 JSON/CSV、UTF-8/引号/换行、消息 ID/会话/双方/类型/正文/ISO 时间、顺序与稳定快照、重启归档、逐条传输和 10000 条流式文件写出均有测试。当时客户端注册/密码/主菜单导出已接入（2026-10-30 已由独立启动页和会话菜单取代）；窗口历史不自动恢复。实际原生弹窗和多机新账号流程待人工验收。

## 启动页和会话导出验收（2026-10-30，历史阶段）

会话导出只含当前公共群或与所选用户的双向私聊，第三方会话和跨范围游标拒绝。启动页连接/认证分离，注册成功自动登录；未认证仅心跳与认证可用。本人资料可修改头像/昵称、重启保留、错误不覆盖旧资料；显式退出/成功完成切换时清除旧身份展示。核心行为与错误边界已纳入 ProfileConnectionTest、AccountFlowTest、AuthenticationExportTest 和 ChatWindowTest。本机自动化/组件视觉检查通过，新流程仍待用户人工与多机验收。

## 切换账号的保留与交接（2026-10-31）

切换操作不会立即退出原账号；候选使用独立连接，原账号继续收消息/心跳。成功 LOGIN_OK 后才关闭旧连接和清理旧界面数据；失败、名额不足、取消不影响旧会话。认证过程中允许返回聊天，取消候选后迟到回调不得切换身份；跨服务端同名账号也清除旧展示记录。验证入口为 ClientSessionsTest、ChatWindowTest。

## 历史与本人副本管理（2026-11-01，历史阶段）

用户明确选择同时支持逐条删除和清空当前会话。已保存的本人归档每次登录可恢复到群/私聊，离线对象也可阅读；不补离线未投递或登录前公共历史。删除服务端本人副本，不能影响对方/第三人，成功确认后不再恢复/导出，失败保留；删除与并发投递须有清楚顺序。保存窗口同窗选择类型/位置/文件名，JSON 每条一行且可标准解析。验证入口 HistoryPersistenceTest、HistoryClientTest、HistoryStoreTest、ChatWindowTest、HistoryExportTest。

## 外观可读性与直观选色（2026-11-02）

暗色主要表面使用更深的中性灰黑；HEX 附近提供可拖动色盘/色相条，同时保留手填、键盘微调及草稿预览/应用/取消。纯色不启用结束色，浅深色选择互相独立。连接/登录及共用按钮的禁用文字清晰、无默认浮雕重影，保持原业务启用条件。旧配置兼容且保留自选色，旧默认暗色映射墨黑。验证入口 ColorPickerTest、ThemeRenderingTest、AppearancePreferencesTest 及既有外观/窗口回归。
