# LTalk 协议 v2（账号与导出）

## 帧与状态

TCP 长连接，**UTF-8 编码，每行一个 JSON 对象，以 LF (`0x0A`) 结尾**。可接受 CRLF（CR 计入帧字节数，JSON 解析时视为空白）。JSON 字符串中的换行由 Gson 转义，不能把正文实际换行直接拼接到线上。一连接串行写帧并 flush；不对不同发送者承诺全局消息顺序。

单帧最多 **16384 字节**（不含 LF），在读取第 16385 个非 LF 字节时立即拒绝，不使用无界 readLine。非法 UTF-8、非法 JSON、未知类型、已定义字段的错误类型或显式 null 返回 `BAD_MESSAGE`，可继续下一帧；超长帧返回 `FRAME_TOO_LARGE` 后关闭连接。末尾无 LF 的残帧遇到 EOF 直接关闭。未定义额外字段被忽略，以便兼容。

连接先处于未登录状态，允许 `REGISTER` 注册及 `LOGIN` 建立身份；其他请求返回 `NOT_AUTHENTICATED`。合法 LOGIN 校验失败返回错误并关闭新连接。重复 LOGIN 返回 `ALREADY_LOGGED_IN`，保留已有身份。未完成登录的连接在接受后 **10 秒绝对期限**关闭（不保证收到错误帧）；客户端连接超时 3 秒、登录读取超时 5 秒，另有连接开始后 8 秒的总期限。正常登录后取消普通读取超时。

`LOGIN_OK` 一定先于该客户端收到的业务事件；服务端先预留名称、写出 LOGIN_OK，再将会话置为就绪。群聊、私聊和在线列表只路由到就绪会话。

## 字段与消息类型

所有对象必有字符串 `type`，下面只列相关字段，未使用字段不输出。REGISTER、LOGIN、EXPORT_REQUEST、GROUP_SEND、PRIVATE_SEND 必须携带非空白、最多 64 个字符的 `requestId`；客户端按连接生成递增编号，不重复使用。服务端不把 requestId 当消息去重键，也不提供幂等重试；不同用户的编号可以相同。

| 类型 | 方向 | 字段 | 结果 |
| --- | --- | --- | --- |
| LOGIN | C → S | requestId, userId, password | 成功 LOGIN_OK；校验失败 ERROR 后关闭 |
| LOGIN_OK | S → 当前 C | requestId, userId | 客户端切为 CONNECTED |
| GROUP_SEND | C → S | requestId, content | 当前全部就绪会话收到 GROUP_MESSAGE，包括发送者 |
| GROUP_MESSAGE | S → 在线 C | requestId, from, content, serverTime | 群聊展示，以服务端事件为唯一正式回显 |
| PRIVATE_SEND | C → S | requestId, to, content | 先写目标，成功后回显发送者；失败只向发送者 ERROR |
| PRIVATE_MESSAGE | S → 私聊双方 | requestId, from, to, content, serverTime | 仅双方收包，不通过广播 |
| USER_LIST | S → 在线 C | users: string[], revision: 非负 long | 全量列表，按较新版本覆盖 |
| SYSTEM | S → 在线 C | content, serverTime | 上下线提示，不作为列表数据来源 |
| ERROR | S → 当前 C | code, message, requestId（可选） | 本次请求失败；能识别合法 requestId 时附回 |
| LOGOUT | C → S | 无其他必需字段 | 关闭连接、幂等清理、推送在线列表 |
| PING | C ↔ S | requestId | 登录后向当前连接发起探测，对端回复 PONG |
| PONG | C ↔ S | requestId | 原样返回该 PING 的编号，只确认当前连接匹配的待响应探测 |

`userId` strip 首尾空白后必须匹配 `[A-Za-z0-9_]{1,20}`，区分大小写；只代表当前连接的路由名称。`to` 必须是精确匹配的合法用户标识，不做目标名称自动规范化。`content` 不为 null、不能全为空白、最长 2000 个 Java UTF-16 char 单元，保留前后空白和换行。所有字符串都受帧字节上限约束。

`from` 和 `serverTime` 由服务端根据会话身份和 `Instant.now()` 生成，**不采信客户端请求的同名值**。时间为 ISO-8601 UTC，如 `2026-10-24T13:00:00Z`；客户端转为当地 HH:mm:ss 显示。SYSTEM 也使用服务端时间。

`revision` 在单个服务端进程内递增。就绪成员变化、版本增加、快照生成在同一注册表短锁内完成，所有网络发送都在该锁外。并发快照发送可能乱序，因此客户端忽略小于或等于已见版本的快照；重新连接将版本重置为 -1。列表按名称排序，包含自己。

## 心跳与失联期限

两端各自在登录成功后启动独立心跳，不依赖聊天活动；未登录的 PING/PONG 返回 `NOT_AUTHENTICATED`，仍受原登录绝对期限限制。PING/PONG 的 `requestId` 必须非空白且最多 64 字符，服务端遇到无效编号返回 `BAD_MESSAGE`，客户端收到无效编号按协议错误断开。心跳仅走当前连接，不广播、不生成聊天记录、未读或业务回调。

默认启动后等待 **10 秒**发出第一条 PING，每端最多一个未完成探测；收到编号匹配的 PONG 后，再等待 10 秒发起下一次。每次从探测入队前开始等待 **20 秒**，入队、写入等待都计入响应期限，避免写线程阻塞时失去检测。合法但不匹配、重复或过期的 PONG 被忽略；普通聊天、SYSTEM、ERROR、对方的 PING 和不完整帧均不能延长本端待响应期限。

无 EOF/RST 的持续失联，按本端配置在**间隔 + 响应超时 + 调度容差**内关闭本地 Socket；默认约 30 秒加容差。计时使用调度器的相对时间，不依赖墙上时钟。JVM/系统暂停、线程调度延迟和网络拥塞会影响实际耗时，这不是硬实时保证；瞬时丢包若在期限内恢复并完成探测，可继续原连接。超时直接关闭，无必需 ERROR 帧；服务端释放名称及连接名额、推送新版在线列表，客户端更新 DISCONNECTED 并提示手动重连，不自动重发未确认消息。

可在各端启动 JVM 时设置正整数毫秒系统属性 `ltalk.heartbeat.intervalMillis`（默认 10000）与 `ltalk.heartbeat.timeoutMillis`（默认 20000，均不超过 2147483647）；两端可独立配置，无需交换时长。非法配置拒绝创建网络客户端/服务端。过短配置可能受调度/延迟影响误判，应根据网络环境保留余量。

本扩展未加入版本协商，**服务端和客户端须一起升级**；旧端不支持 PING/PONG，不能宣称混合版本兼容。原聊天、私聊与列表字段及路由语义保持不变。

## 错误码

| code | 原因 | 连接处理 |
| --- | --- | --- |
| BAD_MESSAGE | 非法 JSON/UTF-8、未知或方向错误的 type、缺字段、错误类型、无效 requestId/to | 返回错误，通常保留连接；合法 LOGIN 校验失败时关闭 |
| NOT_AUTHENTICATED | 未登录就发送其他请求 | 保留，仍可 LOGIN |
| ALREADY_LOGGED_IN | 同一连接重复 LOGIN | 保留已有身份 |
| INVALID_USER_ID | 登录标识不符合规则 | 返回错误并关闭新连接 |
| USER_EXISTS | 同名名称已被在线/预留会话占用 | 拒绝新连接，原会话不受影响 |
| EMPTY_CONTENT | 正文全为空白 | 保留连接 |
| CONTENT_TOO_LONG | 正文超过 2000 char | 保留连接 |
| FRAME_TOO_LARGE | 帧超过 16 KiB | 返回错误并关闭 |
| USER_OFFLINE | 目标不在就绪表中或向目标写入失败 | 不发送成功回显，保留发送者连接 |
| SELF_PRIVATE_NOT_ALLOWED | 私聊目标为自己 | 保留连接 |

连接上限已满时新 TCP 连接直接关闭，避免在接受线程同步写错误阻塞。未登录超时、EOF、RST、关闭服务端以关闭连接表示，无额外必需 ERROR 帧。客户端本地的空白/超长等校验不会发出网络请求，也不会关闭已有连接。

## 示例

以下各行为独立帧：

```json
{"type":"REGISTER","requestId":"reg1","userId":"alice","nickname":"Alice","password":"example-pass"}
{"type":"REGISTER_OK","requestId":"reg1","userId":"alice"}
{"type":"LOGIN","requestId":"r1","userId":"alice","password":"example-pass"}
{"type":"LOGIN_OK","requestId":"r1","userId":"alice"}
{"type":"USER_LIST","users":["alice","bob"],"revision":2}
{"type":"GROUP_SEND","requestId":"r2","content":"大家好\n第二行🙂"}
{"type":"GROUP_MESSAGE","requestId":"r2","from":"alice","content":"大家好\n第二行🙂","serverTime":"2026-10-24T13:00:00Z"}
{"type":"PRIVATE_SEND","requestId":"r3","to":"bob","content":"P-001"}
{"type":"PRIVATE_MESSAGE","requestId":"r3","from":"alice","to":"bob","content":"P-001","serverTime":"2026-10-24T13:00:01Z"}
{"type":"ERROR","requestId":"r4","code":"USER_OFFLINE","message":"目标用户不在线，请重新选择"}
{"type":"PING","requestId":"h1"}
{"type":"PONG","requestId":"h1"}
{"type":"LOGOUT"}
```

发送成功回显只说明服务端完成当次转发写入，不保证接收者已读。断线边缘可能交付不确定；无消息补发、自动重试或窗口历史恢复，心跳也不提供消息送达确认。

## 注册、身份与导出扩展（2026-10-29）

所有需要身份的请求（含每次导出翻页、PING/PONG、LOGOUT）必须来自已完成密码验证的就绪连接；不采信请求的 userId/from 代替绑定身份。凭证就是服务端持有的连接会话，不能跨 Socket 复用，无独立 token/续签/固定 TTL；LOGOUT、EOF、读写失败、服务端关闭、心跳超时均令会话失效，新连接未认证。注册仍受接受后 10 秒绝对期限限制；注册响应不建立身份，须随后 LOGIN。密码和昵称不写日志/聊天事件。

| 类型 | 方向 | 字段/结果 |
| --- | --- | --- |
| REGISTER | C→S，未登录 | requestId, nickname, password；usernameMode 默认 MANUAL，须 userId；RANDOM 模式由服务端生成唯一 userId |
| REGISTER_OK | S→C | requestId, userId；注册成功，可使用该用户名登录 |
| LOGIN | C→S | requestId, userId, password；成功 LOGIN_OK，错误 ERROR 后关闭，重复登录保留原身份 |
| EXPORT_REQUEST | C→S，已登录 | requestId；首次省略 cursor 开始自己归档的快照，后续附返回的 cursor；可带自己的 userId，指定其他用户 FORBIDDEN |
| EXPORT_PAGE | S→C | requestId, cursor, record；每页恰一条记录，客户端流式写出后才请求下一页 |
| EXPORT_END | S→C | requestId；没有剩余记录，空归档直接返回此帧 |

nickname strip 后 1～40 char；userId 沿用 1～20 ASCII 字母/数字/下划线、区分大小写，用户名全局唯一。密码必填、6～128 Java UTF-16 char，不做 strip，数字/字母/特殊字符均允许；仅服务端保存随机盐及 PBKDF2-HMAC-SHA256 600000 次的 256 位散列。系统用户名为 u_ 加 16 位随机十六进制，检查占用后落盘；注册串行化，重复不会覆盖原账号。

新增错误：MISSING_FIELD 缺必填参数；INVALID_NICKNAME 昵称越界；INVALID_PASSWORD 密码长度非法；INVALID_USER_ID 用户名非法；USER_EXISTS 注册重名或已有在线会话；LOGIN_FAILED 统一用户名或密码错误（不区分不存在与密码错误）；NOT_AUTHENTICATED 未认证；FORBIDDEN 他人历史或无效/过期/跨连接游标；STORAGE_ERROR 注册/归档文件失败；EXPORT_FAILED 历史读取失败。原 BAD_MESSAGE 继续处理未知模式、方向/类型/帧错误。客户端本地文件错误或逐页响应超时也提示 EXPORT_FAILED，不自动重试或断开健康聊天连接。

record 字段为 messageId（服务端 UUID）、conversationId（GROUP:PUBLIC 或 PRIVATE:按用户名排序的双方）、sender、recipient（群聊为 null）、content、messageType、sentAt（ISO 8601 UTC）。聊天回显另增加 messageId/conversationId，不以此提供幂等重试。记录按服务端生成时间升序写入，时钟回拨以持久化的上次时间钳制，相同时间按追加顺序。归档只保存路由时在线用户有权接收的公共消息、私聊双方消息，不保存离线投递或给新上线者补公共历史。

游标为服务端随机标识，只保存在原认证连接中；开始时固定文件末尾，后续新消息不进入该快照。新导出替换旧游标，完成或连接结束关闭；每连接最多一个快照。不接受文件路径、任意偏移或他人的游标。客户端每次等待一页再请求一页，内存随单帧大小有界；JSON UTF-8 数组（保留 null recipient）、CSV 七列表头及 RFC 风格双引号/换行转义。文件发布失败会删除临时文件；服务端没有返回客户端本地路径的接口。

本版本无兼容的无密码登录，须两端一起升级。TCP 尚无 TLS；文件归档是导出基础，不是自动历史回放或离线消息功能。
