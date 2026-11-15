# LTalk 协议 v6

## 帧与状态

TCP 长连接，**UTF-8 编码，每行一个 JSON 对象，以 LF (`0x0A`) 结尾**。可接受 CRLF（CR 计入帧字节数，JSON 解析时视为空白）。JSON 字符串中的换行由 Gson 转义。一连接串行写帧并 flush。v6 需要双方使用同一版本，连接过程没有版本协商。

普通帧上限为 **16 KiB**（不含 LF）。`LOGIN_OK`、`PROFILE_UPDATE`、`PROFILE_OK`、`DISPLAY_PROFILE` 可使用 **176 KiB** 上限。超过 16 KiB 时必须把字面 `"type"` 作为 JSON 首字段，类型名不得使用转义，编码器按此顺序输出。读取时先检查类型前缀，再校验解码后的类型与长度。非法 UTF-8、非法 JSON、未知类型或字段类型错误返回 `BAD_MESSAGE`，可继续下一帧。超长帧返回 `FRAME_TOO_LARGE` 后关闭连接。末尾无 LF 的残帧遇到 EOF 直接关闭。未定义额外字段被忽略。

新客户端先发 HELLO，收到 HELLO_OK 后进入仅服务器已连接状态；双方启动心跳，允许等待用户填写登录/注册信息，仍不具有任何业务权限。服务端接受后 10 秒绝对期限要求完成 HELLO 或直接 LOGIN；HELLO 成功取消该期限，由双向心跳检测静默失联。未认证连接仍占总名额（默认 20），不进入在线列表。重复 HELLO 拒绝。

HELLO 后允许 REGISTER/LOGIN 和 PING/PONG；其他业务返回 NOT_AUTHENTICATED。错密码/注册失败保留这条连接以便重试。保留旧直接 REGISTER/LOGIN 入口及其 10 秒期限，未 HELLO 的直接 LOGIN 失败仍关闭连接。重复 LOGIN 不切换当前身份。客户端连接超时默认 3 秒、握手总期限 8 秒，单次注册/登录响应期限 5 秒；超过期限关闭连接，防止迟到认证结果建立意外身份。

`LOGIN_OK` 一定先于该客户端收到的业务事件；服务端先预留名称、写出 LOGIN_OK，再将会话置为就绪。群聊、私聊和在线列表只路由到就绪会话。

## 字段与消息类型

所有对象必有字符串 `type`，未使用字段不输出。HELLO、REGISTER、LOGIN、PROFILE_GET、PROFILE_UPDATE、PASSWORD_CHANGE、EXPORT_REQUEST、HISTORY_REQUEST、HISTORY_DELETE、GROUP_SEND 和 PRIVATE_SEND 均需携带非空白、最多 64 个字符的 `requestId`。PING/PONG 也需要编号。客户端按连接生成请求编号，不同用户的编号可以相同。聊天的消息 ID 由服务端生成，请求编号只用于关联请求和响应。

| 类型 | 方向 | 字段 | 结果 |
| --- | --- | --- | --- |
| LOGIN | C → S | requestId, userId, password | 成功 LOGIN_OK；HELLO 模式失败保留连接，直接登录失败关闭 |
| LOGIN_OK | S → 当前 C | requestId, userId, nickname, avatar, createdAt（旧账号可缺省） | 客户端切为 CONNECTED |
| GROUP_SEND | C → S | requestId, content | 当前全部就绪会话收到 GROUP_MESSAGE，包括发送者 |
| GROUP_MESSAGE | S → 在线 C | requestId, messageId, conversationId, from, content, serverTime, nickname（可选） | 群聊展示，以服务端事件为唯一正式回显 |
| PRIVATE_SEND | C → S | requestId, to, content | 先写目标，成功后回显发送者；失败只向发送者 ERROR |
| PRIVATE_MESSAGE | S → 私聊双方 | requestId, messageId, conversationId, from, to, content, serverTime, nickname（可选） | 仅双方收包，不通过广播 |
| USER_LIST | S → 在线 C | users: string[], revision: 非负 long | 全量列表，按较新版本覆盖 |
| SYSTEM | S → 在线 C | content, serverTime | 上下线提示，不作为列表数据来源 |
| ERROR | S → 当前 C | code, message, requestId（可选） | 本次请求失败；能识别合法 requestId 时附回 |
| LOGOUT | C → S | 无其他必需字段 | 关闭连接、幂等清理、推送在线列表 |
| PING | C ↔ S | requestId | HELLO 或登录成功后向当前连接发起探测，对端回复 PONG |
| PONG | C ↔ S | requestId | 原样返回该 PING 的编号，只确认当前连接匹配的待响应探测 |

`userId` strip 首尾空白后必须匹配 `[A-Za-z0-9_]{1,20}`，区分大小写；只代表当前连接的路由名称。`to` 必须是精确匹配的合法用户标识，不做目标名称自动规范化。`content` 不为 null、不能全为空白、最长 2000 个 Java UTF-16 char 单元，保留前后空白和换行。所有字符串都受帧字节上限约束。

`from` 取自连接身份。聊天事件由服务端生成 UUID 和会话 ID，时间读取服务端时钟并按归档时间下限钳制。时间为 ISO-8601 UTC，例如 `2026-10-24T13:00:00Z`，客户端气泡以当地 HH:mm 展示。SYSTEM 也使用服务端时间。

`revision` 在单个服务端进程内递增。就绪成员变化、版本增加、快照生成在同一注册表短锁内完成，所有网络发送都在该锁外。并发快照发送可能乱序，因此客户端忽略小于或等于已见版本的快照；重新连接将版本重置为 -1。列表按名称排序，包含自己。

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
| USER_OFFLINE | 目标不在就绪表中或向目标写入失败 | 不发送成功回显，保留发送者连接 |
| SELF_PRIVATE_NOT_ALLOWED | 私聊目标为自己 | 保留连接 |
| WRONG_PASSWORD | 修改密码时当前密码不匹配 | 保留连接，可重新填写 |

连接上限已满时新 TCP 连接直接关闭，避免在接受线程同步写错误阻塞。未登录超时、EOF、RST、关闭服务端以关闭连接表示，无额外必需 ERROR 帧。客户端本地的空白/超长等校验不会发出网络请求，也不会关闭已有连接。

## 示例

以下为当前格式的帧示例。实际连接还会收到在线名单、展示资料和系统提示。

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
{"type":"PRIVATE_SEND","requestId":"r3","to":"bob","content":"P-001"}
{"type":"PRIVATE_MESSAGE","requestId":"r3","from":"alice","to":"bob","content":"P-001","serverTime":"2026-10-24T13:00:01Z","nickname":"Alice","messageId":"7b89d42a-b45a-4dd7-95e5-6627b23c5de1","conversationId":"PRIVATE:alice:bob"}
{"type":"ERROR","requestId":"r4","code":"USER_OFFLINE","message":"目标用户不在线，请重新选择"}
{"type":"PING","requestId":"h1"}
{"type":"PONG","requestId":"h1"}
{"type":"PASSWORD_CHANGE","requestId":"p1","password":"example-pass","newPassword":"example-new"}
{"type":"PASSWORD_CHANGED","requestId":"p1"}
{"type":"LOGOUT"}
```

服务端回显是客户端的正式消息来源。私聊回显在目标连接写入成功后发送，群聊写入按就绪连接逐个进行。断线时投递结果可能不确定。重新登录会分页恢复本人授权归档，未确认发送需要用户自行处理。

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

record 字段为 messageId（服务端 UUID）、conversationId（GROUP:PUBLIC 或 PRIVATE:按用户名排序的双方）、sender、recipient（群聊为 null）、content、messageType、sentAt（ISO 8601 UTC）。聊天回显另增加 messageId/conversationId，不以此提供幂等重试。记录按服务端生成时间升序写入，时钟回拨以持久化的上次时间钳制，相同时间按追加顺序。归档只保存路由时在线用户有权接收的公共消息、私聊双方消息，不保存离线投递或给新上线者补公共历史。

游标为原认证连接私有的随机标识，开始时固定文件末尾，新消息不进入该快照。导出和历史查询各有一个独立快照，同类新请求替换旧游标，完成或连接结束时关闭。客户端消费一页后再请求下一页。导出为 JSON UTF-8 数组（保留 null recipient），或带七列表头和双引号转义的 CSV。客户端负责本地文件路径，发布失败会清理临时文件。

TCP 尚无 TLS。现有归档用于本人历史恢复和导出，范围以账号此前收到的授权记录为准。

## 按会话导出（2026-10-30）

EXPORT_REQUEST 可增加 conversationId：GROUP:PUBLIC 或 PRIVATE:按用户名排序的双方，私聊双方必须包含当前认证用户。服务端在本人授权归档内过滤，不扩大历史可见范围。省略字段保留底层全账号导出能力；新版界面只提供当前会话导出。

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

所有历史操作须认证，仅使用连接绑定的账号文件；可选 userId 只能是本人。群/私聊范围沿用 GROUP:PUBLIC 与 PRIVATE:排序双方，私聊必须包含本人。

| 类型 | 字段/行为 |
| --- | --- |
| HISTORY_REQUEST | requestId；首次无 cursor，开始本人归档固定末尾快照；可选 conversationId 筛选。后续 cursor、范围须匹配，跨连接/范围拒绝 |
| HISTORY_PAGE | requestId, cursor, record, nickname（可选，顶层）；每页一条，record 与导出模型一致，客户端展示消费后再请求下一页 |
| HISTORY_END | requestId；无剩余记录，空归档直接返回 |
| HISTORY_DELETE | requestId, conversationId（必填）；无 messageId 清空当前会话的本人副本，有 messageId 删除本会话中该条本人副本。userId 可省略/本人；不接受他人文件/任意路径 |
| HISTORY_DELETED | requestId, conversationId, messageId（逐条时）；仅在本人文件替换成功后确认，不广播给对方 |

历史与导出各占一个独立游标，均只属于原认证连接；新查询替换旧历史游标。删除会关闭该账号已有历史/导出快照，使旧游标失效；客户端重新查询已删除后的归档。不存在的单条记录返回 NOT_FOUND，无会话/无效编号为 BAD_MESSAGE，他人账号/私聊/无效游标为 FORBIDDEN，文件失败为 STORAGE_ERROR。删除写同目录临时文件，成功原子替换；解析/写出失败保留旧文件、不确认成功，不能以删除绕过损坏文件恢复。

聊天归档+投递与删除确认串行排序，防止删除前的实时消息在确认后重新进入本人窗口；新产生的消息正常保存。该顺序仍受原同步网络写入慢端限制，不代表全系统所有事件的全局顺序或送达确认。查询只回放本人此前有权接收的归档，不补上线前公共历史/离线投递。客户端以 messageId 合并历史与实时消息，不使用 requestId 去重；历史不增加未读。超时/失败显示可重试，删除超时结果可能已生效，重新加载核对。

这些历史查询与删除类型自 v4 引入，v6 沿用上述授权与分页规则。

## 消息展示昵称（2026-11-15，v4 可选扩展）

实时 `GROUP_MESSAGE`、`PRIVATE_MESSAGE` 的 `nickname` 由服务端按已认证的 `from` 读取账号资料。发送请求中的 `from` 和 `nickname` 不参与决定正式事件的发送者或昵称。`HISTORY_PAGE` 按已授权记录的 `record.sender` 查阅账号当前昵称，作为帧顶层字段附加，离线账号也可解析。发送者无法查到资料或对端省略字段时，客户端显示稳定用户名。

消息仍按 `from`、`to` 和会话标识路由。昵称随已授权的消息投递范围返回，私聊仅双方。头像通过 `DISPLAY_PROFILE` 同步，注册时间走本人资料接口。旧归档可直接查询，归档和 JSON/CSV 导出记录的字段保持原格式。缺少展示资料时，客户端使用消息携带的昵称，缺少昵称时回退用户名。

## 用户展示资料同步（2026-11-15，v5）

`DISPLAY_PROFILE` 是仅服务端发送的事件。字段为 `userId`、`nickname`、`avatar`、`revision`，其中 `revision` 是该账号独立的非负 long 版本。每次成功保存资料后递增，并和账号一起原子持久化。旧账号默认版本 0。该事件只含展示所需的资料，注册时间仍只发给本人。客户端向服务端发送此类型会被拒绝。

登录进入就绪状态后，新会话收到在线账号资料，其他就绪会话收到新登录账号的资料。资料修改成功后，新的展示资料分发给就绪会话。浏览本人历史时，服务端先发送对应发送者以及私聊接收者的最新资料，再发送该历史页。这样离线联系人也可恢复展示，同一连接中每个账号只接收更新版本。未登录的连接没有资料同步，也没有按用户名查询任意账号资料的接口。

展示资料独立成帧，v6 上限为 176 KiB。`USER_LIST` 继续承载用户名与在线列表版本，聊天、历史和导出记录维持 16 KiB 帧上限。收到旧版本资料时保留新版本。请同时升级客户端与服务端。

## 修改本人密码（v6）

`PASSWORD_CHANGE` 由已认证的客户端发出。必填 `requestId`、`password`（当前密码）、`newPassword`（新密码）。可选 `userId` 必须与连接绑定账号一致，他人账号返回 `FORBIDDEN`，未认证返回 `NOT_AUTHENTICATED`。密码字段须为字符串。缺省或空字符串返回 `MISSING_FIELD`，长度超出 6～128 UTF-16 单元返回 `INVALID_PASSWORD`，保留首尾空白。当前密码不匹配返回 `WRONG_PASSWORD`。

服务端校验当前密码后生成新随机盐，以现有 PBKDF2 参数计算散列。账号文件原子替换成功后才通过 `PASSWORD_CHANGED` 回复原请求编号，回复不含密码，不广播。存储失败返回 `STORAGE_ERROR`，旧密码依然有效。当前连接保持已登录状态，下次登录用新密码。

同一连接的客户端同时等待至多一个改密响应。超时提示修改结果未确认并建议重新登录核对。迟到回执忽略，客户端不自动重试。
