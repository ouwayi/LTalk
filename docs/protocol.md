# LTalk 协议 v6

## 帧与状态

聊天使用 TCP 长连接，**UTF-8 编码，每行一个 JSON 对象，以 LF (`0x0A`) 结尾**。可接受 CRLF（CR 计入帧字节数，JSON 解析时视为空白）。JSON 字符串中的换行由 Gson 转义。一连接串行写帧并 flush。v6 需要双方使用同一版本，连接过程没有版本协商。独立文件连接在控制帧之间按声明长度传输原始字节。

普通帧上限为 **16 KiB**（不含 LF）。`LOGIN_OK`、`PROFILE_UPDATE`、`PROFILE_OK`、`DISPLAY_PROFILE` 可使用 **176 KiB** 上限。超过 16 KiB 时必须把字面 `"type"` 作为 JSON 首字段，类型名不得使用转义，编码器按此顺序输出。读取时先检查类型前缀，再校验解码后的类型与长度。非法 UTF-8、非法 JSON、未知类型或字段类型错误返回 `BAD_MESSAGE`，可继续下一帧。超长帧返回 `FRAME_TOO_LARGE` 后关闭连接。末尾无 LF 的残帧遇到 EOF 直接关闭。未定义额外字段被忽略。

新客户端先发 HELLO，收到 HELLO_OK 后进入仅服务器已连接状态。双方启动心跳并等待登录或注册。服务端接受连接后 10 秒内须完成初始握手。TLS 模式的初始期限包含 TLS 握手，HELLO 成功后另有默认 60 秒完成认证。未认证连接仍占总名额，TLS 模式还设有独立上限。重复 HELLO 被拒绝。

HELLO 后允许 REGISTER/LOGIN 和 PING/PONG；其他业务返回 NOT_AUTHENTICATED。错密码/注册失败保留这条连接以便重试。保留旧直接 REGISTER/LOGIN 入口及其 10 秒期限，未 HELLO 的直接 LOGIN 失败仍关闭连接。重复 LOGIN 不切换当前身份。客户端连接超时默认 3 秒、握手总期限 8 秒，单次注册/登录响应期限 5 秒；超过期限关闭连接，防止迟到认证结果建立意外身份。

`LOGIN_OK` 一定先于该客户端收到的业务事件；服务端先预留名称、写出 LOGIN_OK，再将会话置为就绪。群聊、私聊和在线列表只路由到就绪会话。

## 字段与消息类型

所有对象必有字符串 `type`，未使用字段不输出。HELLO、REGISTER、LOGIN、PROFILE_GET、PROFILE_UPDATE、PASSWORD_CHANGE、EXPORT_REQUEST、HISTORY_REQUEST、HISTORY_VIEW_REQUEST、HISTORY_DELETE、GROUP_SEND、PRIVATE_SEND 和 SOCIAL_REQUEST 均需携带非空白、最多 64 个字符的 `requestId`。PING/PONG 也需要编号。客户端按连接生成请求编号，不同用户的编号可以相同。聊天的消息 ID 由服务端生成，请求编号只用于关联请求和响应。

| 类型 | 方向 | 字段 | 结果 |
| --- | --- | --- | --- |
| LOGIN | C → S | requestId, userId, password | 成功 LOGIN_OK；HELLO 模式失败保留连接，直接登录失败关闭 |
| LOGIN_OK | S → 当前 C | requestId, userId, nickname, avatar, createdAt（旧账号可缺省） | 客户端切为 CONNECTED |
| GROUP_SEND | C → S | requestId, content, 可选 conversationId、replyTo | 公共聊天室发往全部就绪会话；独立群组校验成员并仅投递给就绪成员。均含发送者回显 |
| GROUP_MESSAGE | S → 在线 C | messageId, conversationId, from, content, serverTime, 可选 requestId、nickname、replyTo、attachment | 群聊展示，以服务端事件为唯一正式回显 |
| PRIVATE_SEND | C → S | requestId, to, content, 可选 replyTo | 必须是好友，或已获许可且目标在线。归档后在线目标先入投递队列，离线好友从其归档收取。最后回显发送者 |
| PRIVATE_MESSAGE | S → 私聊双方的就绪连接 | messageId, conversationId, from, to, content, serverTime, 可选 requestId、nickname、replyTo、attachment | 仅双方接收，离线好友通过历史读取 |
| USER_LIST | S → 在线 C | users: string[], revision: 非负 long | 全量列表，按较新版本覆盖 |
| SYSTEM | S → 在线 C | content, serverTime | 上下线提示，不作为列表数据来源 |
| ERROR | S → 当前 C | code, message, requestId（可选） | 本次请求失败；能识别合法 requestId 时附回 |
| LOGOUT | C → S | 无其他必需字段 | 关闭连接、幂等清理、推送在线列表 |
| PING | C ↔ S | requestId | HELLO 或登录成功后向当前连接发起探测，对端回复 PONG |
| PONG | C ↔ S | requestId | 原样返回该 PING 的编号，只确认当前连接匹配的待响应探测 |

`userId` strip 首尾空白后必须匹配 `[A-Za-z0-9_]{1,20}`，区分大小写；只代表当前连接的路由名称。`to` 必须是精确匹配的合法用户标识，不做目标名称自动规范化。`content` 不为 null、不能全为空白、最长 2000 个 Java UTF-16 char 单元，保留前后空白和换行。所有字符串都受帧字节上限约束。

`from` 取自连接身份。聊天事件由服务端生成 UUID 和会话 ID，时间读取服务端时钟并按归档时间下限钳制。时间为 ISO-8601 UTC，例如 `2026-10-24T13:00:00Z`，客户端气泡以当地 HH:mm 展示。SYSTEM 也使用服务端时间。

桌面客户端用唯一请求编号关联待确认的发送。收到发送账号、会话、正文及引用编号均匹配的实时回显后，将本地气泡替换为服务端事件。等待期限默认从入队起 5 秒，尚未开始写入的消息在超时或断开时取消发送。已开始写入后未收到回显、出现 `STORAGE_ERROR` 或 `DELIVERY_UNCONFIRMED` 时保留待确认状态。迟到的匹配回显仍可确认该消息。历史记录无发送请求编号，客户端仅按服务端消息 ID 合并正式记录。请求编号没有去重保证。

`revision` 在单个服务端进程内递增。就绪成员变化、版本增加、快照生成在同一注册表短锁内完成，所有网络发送都在该锁外。并发快照发送可能乱序，因此客户端忽略小于或等于已见版本的快照；重新连接将版本重置为 -1。列表按名称排序，包含自己。

## TLS 与公网准入

TLS 模式对监听端口统一启用 TLS 1.2/1.3，服务端从 PKCS12 加载私钥与证书链。客户端在发送 HELLO 或凭据前验证证书信任链与输入的主机名。握手失败即关闭连接，不尝试明文回退。TLS 完成后仍使用原有 v6 LF 分帧和请求类型。TLS 模式关闭 UDP 发现。

服务端在接受连接时限制总连接、未认证数量及同 IP 接入次数，达到上限的连接直接关闭。登录、注册和改密受每分钟频率限制与密码散列并发限制。属性和启动示例见 [部署手册](deployment.md)。

## IPv4 局域网发现

发现使用独立的 UDP v1 协议，默认发现端口为 9001。客户端尝试各启用网卡的 IPv4 定向广播地址和本机回环地址。服务端对所在链路上的请求单播回复，TCP 仅绑定回环时对回环请求应答。

| 数据报 | 内容 |
| --- | --- |
| 请求 | `LTALK-DISCOVER/1 <nonce>` |
| 应答 | `LTALK-OFFER/1 <nonce> <instanceId> <tcpPort> <name64>` |

各字段用一个 ASCII 空格分隔。每个数据报最多 256 字节。nonce 为本轮随机生成的 32 位小写十六进制串，instanceId 为服务端此次启动的随机标识，格式相同。tcpPort 为实际监听的 1～65535 TCP 端口。name64 为服务器名称 UTF-8 字节的无填充 Base64URL 编码，名称最多 96 个 UTF-8 字节。

客户端只接受本轮 nonce 匹配的应答，从 UDP 源 IPv4 地址取得候选地址。收集约 2 秒，最多保留 32 台，同一实例即使从多个地址回复也合为一项，有回环应答时优先回环。发现本身不建立登录态，随后照常执行 TCP `HELLO / HELLO_OK`，用户再输入凭据。发现名称用于展示，身份验证仍沿用原 TCP 连接方式。

命令行服务端默认开启发现，`-Dltalk.discovery.enabled=false` 关闭。两端可用 `-Dltalk.discovery.port` 配置相同的发现端口，服务端可用 `-Dltalk.discovery.name` 配置显示名。UDP 不可用时 TCP 仍可手动连接。

## 心跳与失联期限

两端各自在 HELLO/HELLO_OK 握手或直接登录成功后启动独立心跳，不依赖聊天活动；未握手且未登录的 PING/PONG 返回 `NOT_AUTHENTICATED`，仍受原绝对期限限制。PING/PONG 的 `requestId` 必须非空白且最多 64 字符，服务端遇到无效编号返回 `BAD_MESSAGE`，客户端收到无效编号按协议错误断开。心跳仅走当前连接，不广播、不生成聊天记录、未读或业务回调。

默认启动后等待 **10 秒**发出第一条 PING，每端最多一个未完成探测；收到编号匹配的 PONG 后，再等待 10 秒发起下一次。每次从探测入队前开始等待 **20 秒**，入队、写入等待都计入响应期限，避免写线程阻塞时失去检测。合法但不匹配、重复或过期的 PONG 被忽略；普通聊天、SYSTEM、ERROR、对方的 PING 和不完整帧均不能延长本端待响应期限。

无 EOF/RST 的持续失联，按本端配置在**间隔 + 响应超时 + 调度容差**内关闭本地 Socket；默认约 30 秒加容差。计时使用调度器的相对时间，不依赖墙上时钟。JVM/系统暂停、线程调度延迟和网络拥塞会影响实际耗时，这不是硬实时保证；瞬时丢包若在期限内恢复并完成探测，可继续原连接。超时直接关闭，无必需 ERROR 帧；服务端释放名称及连接名额、推送新版在线列表，客户端更新 DISCONNECTED 并提示手动重连，不自动重发未确认消息。

可在各端启动 JVM 时设置正整数毫秒系统属性 `ltalk.heartbeat.intervalMillis`（默认 10000）与 `ltalk.heartbeat.timeoutMillis`（默认 20000，均不超过 2147483647）；两端可独立配置，无需交换时长。非法配置拒绝创建网络客户端/服务端。过短配置可能受调度/延迟影响误判，应根据网络环境保留余量。

应用层心跳独立于聊天和在线列表路由。运行时使用同一 v6 版本的客户端与服务端。

## 错误码

| code | 原因 | 连接处理 |
| --- | --- | --- |
| BAD_MESSAGE | 非法 JSON/UTF-8、未知或方向错误的 type、缺字段、错误类型、无效 requestId/to | 返回错误，通常保留连接；合法 LOGIN 校验失败时关闭 |
| NOT_AUTHENTICATED | 未登录就发送其他请求 | 保留，仍可 LOGIN |
| ALREADY_LOGGED_IN | 同一连接重复 LOGIN | 保留已有身份 |
| INVALID_USER_ID | 账号标识不符合规则 | HELLO 模式可重试，直接登录失败则关闭 |
| USER_EXISTS | 注册名已存在，或登录名已被在线/预留会话占用 | 拒绝本次操作，原会话保留。直接登录失败则关闭新连接 |
| EMPTY_CONTENT | 正文全为空白 | 保留连接 |
| CONTENT_TOO_LONG | 正文超过 2000 char | 保留连接 |
| FRAME_TOO_LARGE | 帧超过其类型允许的上限 | 返回错误并关闭 |
| USER_OFFLINE | 私聊请求的目标离线，或尝试向离线非好友发送消息 | 拒绝本次操作，保留连接 |
| PRIVATE_FORBIDDEN | 在线私聊缺少许可，或私聊申请受屏蔽限制 | 拒绝本次操作，保留连接 |
| DELIVERY_UNCONFIRMED | 已写归档，但目标连接在消息进入其发送队列前失效 | 保留发送者连接，提示核对归档 |
| RATE_LIMITED | 认证请求超过频率限制 | HELLO 模式可稍后重试 |
| SERVER_BUSY | 密码散列并发达到上限，等候超时 | HELLO 模式可稍后重试 |
| SELF_PRIVATE_NOT_ALLOWED | 私聊目标为自己 | 保留连接 |
| WRONG_PASSWORD | 修改密码时当前密码不匹配 | 保留连接，可重新填写 |

连接上限已满时新 TCP 连接直接关闭，避免在接受线程同步写错误阻塞。未登录超时、EOF、RST、关闭服务端以关闭连接表示，无额外必需 ERROR 帧。客户端本地的空白/超长等校验不会发出网络请求，也不会关闭已有连接。

## 示例

以下为当前格式的帧示例，假设 alice 与 bob 已建立好友关系。实际连接还会收到在线名单、展示资料和系统提示。

```json
{"type":"HELLO","requestId":"hello"}
{"type":"HELLO_OK","requestId":"hello"}
{"type":"REGISTER","requestId":"reg1","userId":"alice","nickname":"Alice","password":"example-pass"}
{"type":"REGISTER_OK","requestId":"reg1","userId":"alice"}
{"type":"LOGIN","requestId":"r1","userId":"alice","password":"example-pass"}
{"type":"LOGIN_OK","requestId":"r1","userId":"alice","nickname":"Alice","avatar":"","createdAt":"2026-10-24T12:00:00Z"}
{"type":"USER_LIST","users":["alice","bob"],"revision":2}
{"type":"DISPLAY_PROFILE","userId":"alice","nickname":"Alice","avatar":"","revision":0}
{"type":"GROUP_SEND","requestId":"r2","content":"大家好\n第二行🙂"}
{"type":"GROUP_MESSAGE","requestId":"r2","from":"alice","content":"大家好\n第二行🙂","serverTime":"2026-10-24T13:00:00Z","nickname":"Alice","messageId":"46538ebe-da93-423d-98ee-4bd116929586","conversationId":"GROUP:PUBLIC"}
{"type":"GROUP_SEND","requestId":"quote-1","content":"收到","replyTo":"46538ebe-da93-423d-98ee-4bd116929586"}
{"type":"GROUP_MESSAGE","requestId":"quote-1","messageId":"53c40108-dd65-4d51-8079-635bc7e8d269","conversationId":"GROUP:PUBLIC","from":"alice","content":"收到","serverTime":"2026-10-24T13:00:01Z","replyTo":"46538ebe-da93-423d-98ee-4bd116929586"}
{"type":"PRIVATE_SEND","requestId":"r3","to":"bob","content":"P-001"}
{"type":"PRIVATE_MESSAGE","requestId":"r3","from":"alice","to":"bob","content":"P-001","serverTime":"2026-10-24T13:00:02Z","nickname":"Alice","messageId":"7b89d42a-b45a-4dd7-95e5-6627b23c5de1","conversationId":"PRIVATE:alice:bob"}
{"type":"PING","requestId":"h1"}
{"type":"PONG","requestId":"h1"}
{"type":"PASSWORD_CHANGE","requestId":"p1","password":"example-pass","newPassword":"example-new"}
{"type":"PASSWORD_CHANGED","requestId":"p1"}
{"type":"LOGOUT"}
```

服务端回显是客户端的正式消息来源。群聊分发到符合会话范围的就绪连接 FIFO。在线私聊回显表示归档完成且对端队列已接受投递，离线好友留言回显表示归档完成。对端掉线或队列超限后，实时投递可能中断。本人可见归档可在重连后查询。同一连接的聊天事件和删除回执按入队顺序发送。

## 注册、身份与导出扩展（2026-10-29）

所有身份业务请求（含历史和导出翻页、个人资料、改密和 LOGOUT）来自已完成密码验证的就绪连接。身份取自服务端连接会话。LOGOUT、EOF、读写失败、服务端关闭和心跳超时均令会话失效。直接注册仍受接受后 10 秒绝对期限限制，HELLO 后注册由心跳保活。注册响应不建立身份，客户端随后发送 LOGIN，收到 LOGIN_OK 才进入聊天。密码不进入聊天事件和日志，昵称可以作为展示字段随聊天事件发送。

| 类型 | 方向 | 字段/结果 |
| --- | --- | --- |
| REGISTER | C→S，未登录 | requestId, nickname, password；usernameMode 默认 MANUAL，须 userId；RANDOM 模式由服务端生成唯一 userId |
| REGISTER_OK | S→C | requestId, userId；注册成功，可使用该用户名登录 |
| LOGIN | C→S | requestId, userId, password；成功 LOGIN_OK；HELLO 模式错误可重试，直接登录错误关闭；重复登录保留原身份 |
| EXPORT_REQUEST | C→S，已登录 | requestId；首次省略 cursor 开始自己归档的快照，后续附返回的 cursor；可带自己的 userId，指定其他用户 FORBIDDEN |
| EXPORT_PAGE | S→C | requestId, cursor, record；每页恰一条记录，客户端流式写出后才请求下一页 |
| EXPORT_END | S→C | requestId；没有剩余记录，空归档直接返回此帧 |

nickname strip 后 1～40 char；userId 沿用 1～20 ASCII 字母/数字/下划线、区分大小写，用户名全局唯一。密码必填、6～128 Java UTF-16 char，不做 strip，数字/字母/特殊字符均允许；仅服务端保存随机盐及 PBKDF2-HMAC-SHA256 600000 次的 256 位散列。系统用户名为 u_ 加 16 位随机十六进制，检查占用后落盘；注册串行化，重复不会覆盖原账号。

新增错误：MISSING_FIELD 缺必填参数；INVALID_NICKNAME 昵称越界；INVALID_PASSWORD 密码长度非法；INVALID_USER_ID 用户名非法；USER_EXISTS 注册重名或已有在线会话；LOGIN_FAILED 统一用户名或密码错误（不区分不存在与密码错误）；NOT_AUTHENTICATED 未认证；FORBIDDEN 他人历史或无效/过期/跨连接游标；STORAGE_ERROR 注册/归档文件失败；EXPORT_FAILED 历史读取失败。原 BAD_MESSAGE 继续处理未知模式、方向/类型/帧错误。客户端本地文件错误或逐页响应超时也提示 EXPORT_FAILED，不自动重试或断开健康聊天连接。

record 字段为 messageId（服务端 UUID）、conversationId（GROUP:PUBLIC、GROUP:群组 UUID 或 PRIVATE:按用户名排序的双方）、sender、recipient（群聊为 null）、content、messageType、sentAt（ISO 8601 UTC）。聊天回显另增加 messageId/conversationId。新版服务端给新消息分配递增时间，时钟回拨或重复时在上次时间上增加一纳秒。所有已认证账号都可读取公共共享归档，包含登录前和离线期间的消息，旧版公共记录沿用原权限。独立群组按入群、退群和本人删除范围查询。私聊归档仅双方可读，向离线陌生人发送返回 USER_OFFLINE。

游标为原认证连接私有的随机标识，开始时固定文件末尾，新消息不进入该快照。导出和历史查询各有一个独立快照，同类新请求替换旧游标，完成或连接结束时关闭。客户端消费一页后再请求下一页。导出为 JSON UTF-8 数组（保留 null recipient），或带七列表头和双引号转义的 CSV。客户端负责本地文件路径，发布失败会清理临时文件。

历史与导出共享当前账号的可见范围和删除状态。

## 按会话导出（2026-10-30）

EXPORT_REQUEST 可增加 conversationId，由服务端按本人权限过滤。省略字段时使用公共与本人私聊归档，独立群组须指定会话。客户端提供当前会话导出。

首次请求固定会话范围和文件末尾；每次翻页须携带相同 conversationId 和 cursor，改变范围返回 FORBIDDEN。无记录直接 EXPORT_END。非法/非本人会话拒绝，过滤不改变时间顺序、流式写出或文件失败清理语义。

## 分离连接认证与本人资料（2026-10-30）

| 类型 | 方向/字段 | 行为 |
| --- | --- | --- |
| HELLO / HELLO_OK | C→S / S→C，requestId | 仅建立协议连接和双向心跳，不建立账号身份、不广播 |
| PROFILE_GET | C→S，requestId；可选自己的 userId | 仅已认证用户查询自己；他人 userId 返回 FORBIDDEN |
| PROFILE_UPDATE | C→S，requestId, nickname, avatar；可选自己的 userId | 修改本人昵称/头像；用户名、密码材料、注册时间保持不变 |
| PROFILE_OK | S→本人，requestId, userId, nickname, avatar, createdAt（可缺省） | 读取或原子持久化成功后返回。v5 另外分发公开展示字段 |

v6 的 `avatar` 为 Base64 编码的 256×256 或 512×512 PNG/JPEG，兼容旧版 144×144 PNG/JPEG 和 48×48 PNG。解码后最多 128 KiB，空串表示恢复字母头像。字段类型、Base64 长度、图片格式与尺寸经过校验，非法为 INVALID_AVATAR，旧资料保留。昵称 strip 后 1～40 UTF-16 char，空或越界沿用 MISSING_FIELD/INVALID_NICKNAME。图片路径只在客户端本地处理。LOGIN_OK 携带本人这些资料，createdAt 为新账号创建的 ISO UTC。旧账号无记录时不虚构时间。资料响应不包含密码、盐或散列。

资料写失败返回 STORAGE_ERROR，内存保持旧值；客户端最多一个待响应资料请求，默认 5 秒超时提示结果未确认，忽略迟到响应且不终止健康聊天连接。重登可核对服务端最终资料。退出登录关闭认证 Socket 并清除界面身份、历史/草稿和密码；用户明确操作后可新建未认证连接。切换账号则在独立 Socket 执行既有 HELLO/LOGIN，成功后才关闭旧认证 Socket 并清理旧展示数据；取消/失败保持旧会话，取消关闭候选，期间额外占一连接名额。无新协议类型，不恢复账号身份、不自动重发。

客户端和服务端一起升级，无版本协商。稳定用户名继续作为联系人和私聊路由的身份键及归档 ID。v5 展示用资料通过独立事件同步。

## 导出文件布局（2026-11-01）

网络导出帧不变。JSON 文件仍为数组，但括号独占行、每条记录独占一行（除末条外行末逗号），正文内换行由 JSON 转义；空数组 []。不是 JSONL。文件名/保存类型/位置由客户端另存为选择，路径不发送到服务端。

## 本人历史查询与删除（2026-11-01，v4）

所有历史操作须认证，可选 userId 只能是连接绑定账号。私聊范围必须包含本人。独立群组必须有本人的历史授权，删除只修改本人标记。

| 类型 | 字段/行为 |
| --- | --- |
| HISTORY_REQUEST | requestId；首次无 cursor，开始本人归档固定末尾快照；可选 conversationId 筛选。后续 cursor、范围须匹配，跨连接/范围拒绝 |
| HISTORY_PAGE | requestId, cursor, record, nickname（可选，顶层）；每页一条，record 与导出模型一致，客户端展示消费后再请求下一页 |
| HISTORY_END | requestId；无剩余记录，空归档直接返回 |
| HISTORY_DELETE | requestId, conversationId（必填）；无 messageId 清空当前会话的本人副本，有 messageId 删除本会话中该条本人副本。userId 可省略/本人；不接受他人文件/任意路径 |
| HISTORY_DELETED | requestId, conversationId, messageId（逐条时）；仅在本人文件替换成功后确认，不广播给对方 |

历史与导出各占一个独立游标，均只属于原认证连接；新查询替换旧历史游标。删除会关闭该账号已有历史/导出快照，使旧游标失效；客户端重新查询已删除后的归档。不存在的单条记录返回 NOT_FOUND，无会话/无效编号为 BAD_MESSAGE，他人账号/私聊/无效游标为 FORBIDDEN，文件失败为 STORAGE_ERROR。公共删除原子更新当前账号的隐藏记录，私聊删除原子重写当前账号的文件。文件错误时保留原有可见记录。

聊天归档+投递与删除确认串行排序，防止删除前的实时消息在确认后重新进入本人窗口。清空公共会话覆盖当时可见的旧版和共享记录，之后发出的消息正常显示。客户端以 messageId 合并历史与实时消息，历史不增加未读。超时/失败显示可重试，删除超时结果可能已生效，重新加载核对。

这些历史查询与删除类型自 v4 引入，v6 沿用上述授权与分页规则。

## 按需浏览、搜索及定位历史（v6 扩展）

新版桌面客户端使用 `HISTORY_VIEW_REQUEST`。请求携带 `requestId` 和 `historyMode`，可选的 `userId` 须为本人。须升级服务端和客户端才能使用新入口。已有 `HISTORY_REQUEST`、删除、导出和归档文件格式继续适用。

| historyMode | 请求参数与结果 |
| --- | --- |
| SUMMARIES | 可选 `cursor` 为上页末行的会话标识。按会话标识升序返回每个有可见记录的会话的最新一条。 |
| MESSAGES | 必填 `conversationId`；可选 `cursor` 为上页最后一条的消息 UUID。无游标取最新一页，有游标取严格早于该记录的下一批。 |
| SEARCH | 必填 `conversationId` 和 `content`，关键词非空白且最多 128 个 UTF-16 单元。去首尾空白后忽略大小写匹配正文、发送者当前昵称或用户名。可选 `cursor` 为上页末条匹配的 UUID。按从新到旧返回。 |
| CONTEXT | 必填 `conversationId` 和目标 `messageId`。从新到旧返回目标消息及前后各最多 9 条本人可见记录。 |

每次查询返回 0～20 个 `HISTORY_VIEW_ROW`，每行一帧，字段为 `requestId`、`record` 和可选的当前 `nickname`。结束帧 `HISTORY_VIEW_END` 携带同一个 `requestId`，还有记录时包含 `cursor`，定位结果无后续游标。浏览消息为从新到旧。帧上限均为 16 KiB，摘要、浏览和定位涉及的头像仍通过独立的 `DISPLAY_PROFILE` 同步。

每次请求重新按认证账号、会话和删除设置打开快照。消息游标与定位目标必须在该会话的可见记录中，找不到返回 `NOT_FOUND`。参数组合或标识格式错误返回 `BAD_MESSAGE`，他人账号或不含本人的私聊范围返回 `FORBIDDEN`，存储读取失败返回 `STORAGE_ERROR`。追加的实时消息在翻页时不会让先前返回的记录重复进入下一页。客户端根据请求编号消费完整页面。超时、失败或不完整的页面可重试。

## 好友、陌生人私聊与独立群组（v6 扩展）

`SOCIAL_REQUEST` 必填 `requestId` 和 `socialAction`。身份来自连接，可选 `userId` 须与本人一致。查询以最多 24 个 `SOCIAL_ROW` 响应，字段为 `requestId` 和 `socialItem`。随后同编号的 `SOCIAL_END` 表示完成，还有结果时携带 `cursor`。游标为末项的 `kind:id`，用于同类查询。修改请求成功时只有结束帧。服务端发给受影响的就绪会话的 `SOCIAL_CHANGED` 提醒重取状态，可携带好友或私聊请求处理提示。离线账号下次登录后主动查询持久状态。

| socialAction | 参数与授权 |
| --- | --- |
| STATE | 可选 cursor；本人好友、私聊许可、待处理请求与已获历史授权的群组 |
| FIND_USER | content 为昵称或用户名片段，去首尾空白后 1～40 个 UTF-16 单元，忽略大小写。可选 cursor。返回除本人外的匹配账号，包含完整用户名与当前昵称。无匹配时返回空页。省略 content 时仍接受旧客户端用 to 精确查询完整用户名。 |
| FIND_GROUP | content 为可选群名片段或群 UUID，最长 64 字符；可选 cursor；返回存续群组的目录元数据 |
| GROUP_MEMBERS | conversationId 为 `GROUP:<UUID>`，可选 cursor；仅当前群成员可查询，返回该群的当前成员（含离线成员） |
| FRIEND_REQUEST / FRIEND_REMOVE | to 为对方用户名，允许离线；申请需要对方确认 |
| FRIEND_ACCEPT / FRIEND_REJECT | messageId 为申请 ID；只有接收方可处理 |
| PRIVATE_REQUEST | to 为对方用户名，须在线；每对账号至多一个在途请求 |
| PRIVATE_ACCEPT / PRIVATE_REJECT | messageId 为请求 ID；只有接收方可处理 |
| PRIVATE_BLOCK / PRIVATE_UNBLOCK | to 为对方用户名；屏蔽会撤销独立私聊许可和待处理私聊请求 |
| GROUP_CREATE | nickname 为 1～40 字符群名，content 为 `OPEN` 或 `REVIEW` |
| GROUP_REQUEST | conversationId 为 `GROUP:<UUID>`；开放群直接进入，需审核的群产生申请 |
| GROUP_APPROVE / GROUP_REJECT | messageId 为入群申请 ID；由当前管理员处理 |
| GROUP_INVITE | conversationId、to；在群成员可邀请自己的好友 |
| INVITE_ACCEPT / INVITE_REJECT | messageId 为邀请 ID；接收方决定。接受当前管理员的邀请直接入群，其他邀请沿用群的审核设置 |
| GROUP_REVIEW | conversationId、content（`OPEN` 或 `REVIEW`）；由管理员切换审核 |
| GROUP_TRANSFER | conversationId、to；管理员指定另一位当前成员 |
| GROUP_LEAVE / GROUP_DISBAND | conversationId；普通成员可退出，管理员可解散，或先转让再退出 |

`socialItem` 包含 `kind` 和 `id`。用户类可带 `user`、`title`。请求项使用请求 UUID，`user` 为对端，涉及群组时附带 `group`、`title`、`admin` 和 `review`。群目录项附带群 UUID、群名、当前管理员、审核开关和本人的 `member`。已解散群组仅保留在曾获授权成员的 STATE 中，管理员字段省略。

`GROUP_MEMBERS` 的行使用 `GROUP_MEMBER`，`id` 和 `user` 均为完整用户名，`title` 为当前昵称。需要同步的展示资料通过 `DISPLAY_PROFILE` 先于对应行发送。客户端结合 `USER_LIST` 计算在线人数。加入或退出独立群组时，服务端也向其余当前成员发送 `SOCIAL_CHANGED`，打开名单的客户端可刷新各页。每次翻页重新校验当前成员身份。非成员返回 `FORBIDDEN`，不存在或已解散的群返回 `NOT_FOUND`。公共聊天室使用登录后已有的 `USER_LIST`，其范围不适用于本查询。

仅当前群成员可通过带群组会话 ID 的 `GROUP_SEND` 发消息。成员加入时记录当时最近 100 条的起点，退出或解散时关闭授权区间，再次入群增加新的区间。浏览、搜索定位、导出、删除和摘要共同使用这些区间。从未加入的账号无法获取群消息。独立群组使用服务端 UUID，公共聊天室保留 `GROUP:PUBLIC`。

缺少历史权限或操作身份不符返回 `FORBIDDEN`，无私聊许可返回 `PRIVATE_FORBIDDEN`，重复待处理请求返回 `REQUEST_PENDING`，已有关系或成员返回 `ALREADY_EXISTS`，请求失效返回 `NOT_FOUND`。管理员直接退群返回 `ADMIN_REQUIRED`。客户端与服务端需同步升级，旧消息可沿用原数据目录。

## 图片和文件传输（v6 扩展）

Emoji 使用普通文字的 `content`。图片和附件沿用 `GROUP_MESSAGE`、`PRIVATE_MESSAGE` 和历史记录格式。消息的 `content` 为文件名，`messageId` 也是附件的标识。新增可选对象 `attachment`，含 `kind`（`IMAGE` 或 `FILE`）、`name` 和非负整数 `size`（字节）。图片的实时消息和历史行另含 `thumbnail`，为 Base64 JPEG，边长最多 160 像素，编码前的 JPEG 最多 6000 字节。JSON 导出保留元数据，CSV 沿用七列。

| 类型 | 连接 | 方向和字段 |
| --- | --- | --- |
| ATTACH_UPLOAD | 已登录的聊天连接 | C → S。必填 `requestId` 和无缩略图的 `attachment`。群聊用 `conversationId`（省略为公共聊天室），私聊用 `to`。 |
| ATTACH_DOWNLOAD | 已登录的聊天连接 | C → S。必填 `requestId`、`conversationId` 和 `messageId`。 |
| ATTACH_TICKET | 聊天连接 | S → C。含匹配的 `requestId` 和在 `cursor` 中的一次性票据。 |
| ATTACH_CANCEL | 聊天连接 | C → S。带原请求的 `requestId`。取消该请求的票据或关闭正在使用它的传输连接。 |
| ATTACH_OPEN | 独立传输连接首帧 | C → S。用 `cursor` 提交票据。 |
| ATTACH_READY | 独立传输连接 | S → C。`attachment` 给出已核验的传输信息。 |
| ATTACH_COMPLETE | 独立传输连接 | 原始字节之后的完成帧。上传先由客户端发送，服务端已归档及路由后再确认并返回 `messageId`。下载由服务端在末尾发送。 |

客户端连接同一地址和端口。TLS 模式在两条连接上都验证证书与主机名。票据绑定到签发它的认证会话，20 秒内可领取一次。每个登录会话同时持有至多一个票据或传输。传输连接仍占总连接名额，全服务端最多同时传输 8 个，领取后最长 120 秒，单次读取最多等 15 秒。聊天连接保留原有心跳。

收到 `ATTACH_READY` 后，发送端紧接着传输恰好 `size` 个原始字节，随后恢复 LF 分隔的 JSON。上传方提交完成帧后，服务端验证图片并再次检查会话发送权限，保存原文件后按既有聊天规则归档与路由。下载方通过开始下载时本人的可见归档授权访问相应附件。退群、解散和本人删除沿用历史的权限规则。取消、断线或失败时清理未完成的文件。提交上传完成帧后连接断开时，客户端提示核对聊天记录。

图片入口接受 PNG、JPEG、GIF 和 BMP，最大 12 MiB、2400 万像素。普通附件不限类型，最大 50 MiB，可为空文件。服务端以消息 UUID 保存原文件。文件名供展示与建议保存名使用，长度不超过 160 个 Unicode 码点，并清理路径分隔符、控制字符和 Windows 保留名称。

| 错误码 | 条件 |
| --- | --- |
| BAD_ATTACHMENT | 元数据无效或缺少上传完成帧 |
| ATTACHMENT_TOO_LARGE | 超过图片或附件大小限制 |
| INVALID_IMAGE | 图片扩展名、格式、解码或像素数无效 |
| ATTACHMENT_BUSY | 当前会话已有传输或全局传输达到上限 |
| ATTACHMENT_QUOTA | 服务端附件配额不足 |
| ATTACHMENT_NOT_FOUND | 当前记录不可下载或附件文件缺失 |
| FORBIDDEN | 票据无效、已使用或过期，或会话历史无权访问 |

## 引用回复（v6 扩展）

`GROUP_SEND` 和 `PRIVATE_SEND` 可携带 `replyTo`，值为原消息的规范 UUID。服务端只接受发送账号在同一会话中仍可查看的记录，编号无效返回 `BAD_MESSAGE`，原消息已删除、跨会话或超出可见范围返回 `NOT_FOUND`。正式消息事件、JSONL 归档和 JSON 导出保留该编号。旧记录省略该字段，CSV 继续使用原七列。

引用仅传递编号。客户端从当前可见消息取预览，按现有 `CONTEXT` 查询定位未加载原文。原消息可见性取决于查看者本人的群组历史区间和删除设置。客户端匹配发送回显时同时核对引用编号。未读定位使用客户端窗口状态。

## 消息展示昵称（2026-11-15，v4 可选扩展）

实时 `GROUP_MESSAGE`、`PRIVATE_MESSAGE` 的 `nickname` 由服务端按已认证的 `from` 读取账号资料。发送请求中的 `from` 和 `nickname` 不参与决定正式事件的发送者或昵称。`HISTORY_PAGE` 按已授权记录的 `record.sender` 查阅账号当前昵称，作为帧顶层字段附加，离线账号也可解析。发送者无法查到资料或对端省略字段时，客户端显示稳定用户名。

消息仍按 `from`、`to` 和会话标识路由。昵称随已授权的消息投递范围返回，私聊仅双方。头像通过 `DISPLAY_PROFILE` 同步，注册时间走本人资料接口。旧归档可直接查询，归档和 JSON/CSV 导出记录的字段保持原格式。缺少展示资料时，客户端使用消息携带的昵称，缺少昵称时回退用户名。

## 用户展示资料同步（2026-11-15，v5）

`DISPLAY_PROFILE` 是仅服务端发送的事件。字段为 `userId`、`nickname`、`avatar`、`revision`，其中 `revision` 是该账号独立的非负 long 版本。每次成功保存资料后递增，并和账号一起原子持久化。旧账号默认版本 0。该事件只含展示所需的资料，注册时间仍只发给本人。客户端向服务端发送此类型会被拒绝。

登录进入就绪状态后，新会话收到在线账号资料，其他就绪会话收到新登录账号的资料。资料修改成功后，新的展示资料分发给就绪会话。加载摘要、消息或定位上下文时，服务端先发送涉及账号的最新展示资料，再发送历史页。按关键词搜索的结果携带当前昵称。同一连接中每个账号只接收更新版本。已认证账号可通过 `FIND_USER` 按昵称或用户名查找基本目录信息。

展示资料独立成帧，v6 上限为 176 KiB。`USER_LIST` 继续承载用户名与在线列表版本，聊天、历史和导出记录维持 16 KiB 帧上限。收到旧版本资料时保留新版本。请同时升级客户端与服务端。

## 修改本人密码（v6）

`PASSWORD_CHANGE` 由已认证的客户端发出。必填 `requestId`、`password`（当前密码）、`newPassword`（新密码）。可选 `userId` 必须与连接绑定账号一致，他人账号返回 `FORBIDDEN`，未认证返回 `NOT_AUTHENTICATED`。密码字段须为字符串。缺省或空字符串返回 `MISSING_FIELD`，长度超出 6～128 UTF-16 单元返回 `INVALID_PASSWORD`，保留首尾空白。当前密码不匹配返回 `WRONG_PASSWORD`。

服务端校验当前密码后生成新随机盐，以现有 PBKDF2 参数计算散列。账号文件原子替换成功后才通过 `PASSWORD_CHANGED` 回复原请求编号，回复不含密码，不广播。存储失败返回 `STORAGE_ERROR`，旧密码依然有效。当前连接保持已登录状态，下次登录用新密码。

同一连接的客户端同时等待至多一个改密响应。超时提示修改结果未确认并建议重新登录核对。迟到回执忽略，客户端不自动重试。
