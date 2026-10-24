# LTalk 协议 v1

## 帧与状态

TCP 长连接，**UTF-8 编码，每行一个 JSON 对象，以 LF (`0x0A`) 结尾**。可接受 CRLF（CR 计入帧字节数，JSON 解析时视为空白）。JSON 字符串中的换行由 Gson 转义，不能把正文实际换行直接拼接到线上。一连接串行写帧并 flush；不对不同发送者承诺全局消息顺序。

单帧最多 **16384 字节**（不含 LF），在读取第 16385 个非 LF 字节时立即拒绝，不使用无界 readLine。非法 UTF-8、非法 JSON、未知类型、已定义字段的错误类型或显式 null 返回 `BAD_MESSAGE`，可继续下一帧；超长帧返回 `FRAME_TOO_LARGE` 后关闭连接。末尾无 LF 的残帧遇到 EOF 直接关闭。未定义额外字段被忽略，以便兼容。

连接先处于未登录状态，只有 `LOGIN` 能建立身份；其他请求返回 `NOT_LOGGED_IN`。合法 LOGIN 校验失败返回错误并关闭新连接。重复 LOGIN 返回 `ALREADY_LOGGED_IN`，保留已有身份。未完成登录的连接在接受后 **10 秒绝对期限**关闭（不保证收到错误帧）；客户端连接超时 3 秒、登录读取超时 5 秒，另有连接开始后 8 秒的总期限。正常登录后取消普通读取超时。

`LOGIN_OK` 一定先于该客户端收到的业务事件；服务端先预留名称、写出 LOGIN_OK，再将会话置为就绪。群聊、私聊和在线列表只路由到就绪会话。

## 字段与消息类型

所有对象必有字符串 `type`，下面只列相关字段，未使用字段不输出。LOGIN、GROUP_SEND、PRIVATE_SEND 必须携带非空白、最多 64 个字符的 `requestId`；客户端按连接生成递增编号，不重复使用。服务端不把 requestId 当消息去重键，也不提供幂等重试；不同用户的编号可以相同。

| 类型 | 方向 | 字段 | 结果 |
| --- | --- | --- | --- |
| LOGIN | C → S | requestId, userId | 成功 LOGIN_OK；校验失败 ERROR 后关闭 |
| LOGIN_OK | S → 当前 C | requestId, userId | 客户端切为 CONNECTED |
| GROUP_SEND | C → S | requestId, content | 当前全部就绪会话收到 GROUP_MESSAGE，包括发送者 |
| GROUP_MESSAGE | S → 在线 C | requestId, from, content, serverTime | 群聊展示，以服务端事件为唯一正式回显 |
| PRIVATE_SEND | C → S | requestId, to, content | 先写目标，成功后回显发送者；失败只向发送者 ERROR |
| PRIVATE_MESSAGE | S → 私聊双方 | requestId, from, to, content, serverTime | 仅双方收包，不通过广播 |
| USER_LIST | S → 在线 C | users: string[], revision: 非负 long | 全量列表，按较新版本覆盖 |
| SYSTEM | S → 在线 C | content, serverTime | 上下线提示，不作为列表数据来源 |
| ERROR | S → 当前 C | code, message, requestId（可选） | 本次请求失败；能识别合法 requestId 时附回 |
| LOGOUT | C → S | 无其他必需字段 | 关闭连接、幂等清理、推送在线列表 |

`userId` strip 首尾空白后必须匹配 `[A-Za-z0-9_]{1,20}`，区分大小写；只代表当前连接的路由名称。`to` 必须是精确匹配的合法用户标识，不做目标名称自动规范化。`content` 不为 null、不能全为空白、最长 2000 个 Java UTF-16 char 单元，保留前后空白和换行。所有字符串都受帧字节上限约束。

`from` 和 `serverTime` 由服务端根据会话身份和 `Instant.now()` 生成，**不采信客户端请求的同名值**。时间为 ISO-8601 UTC，如 `2026-10-24T13:00:00Z`；客户端转为当地 HH:mm:ss 显示。SYSTEM 也使用服务端时间。

`revision` 在单个服务端进程内递增。就绪成员变化、版本增加、快照生成在同一注册表短锁内完成，所有网络发送都在该锁外。并发快照发送可能乱序，因此客户端忽略小于或等于已见版本的快照；重新连接将版本重置为 -1。列表按名称排序，包含自己。

## 错误码

| code | 原因 | 连接处理 |
| --- | --- | --- |
| BAD_MESSAGE | 非法 JSON/UTF-8、未知或方向错误的 type、缺字段、错误类型、无效 requestId/to | 返回错误，通常保留连接；合法 LOGIN 校验失败时关闭 |
| NOT_LOGGED_IN | 未登录就发送其他请求 | 保留，仍可 LOGIN |
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
{"type":"LOGIN","requestId":"r1","userId":"alice"}
{"type":"LOGIN_OK","requestId":"r1","userId":"alice"}
{"type":"USER_LIST","users":["alice","bob"],"revision":2}
{"type":"GROUP_SEND","requestId":"r2","content":"大家好\n第二行🙂"}
{"type":"GROUP_MESSAGE","requestId":"r2","from":"alice","content":"大家好\n第二行🙂","serverTime":"2026-10-24T13:00:00Z"}
{"type":"PRIVATE_SEND","requestId":"r3","to":"bob","content":"P-001"}
{"type":"PRIVATE_MESSAGE","requestId":"r3","from":"alice","to":"bob","content":"P-001","serverTime":"2026-10-24T13:00:01Z"}
{"type":"ERROR","requestId":"r4","code":"USER_OFFLINE","message":"目标用户不在线，请重新选择"}
{"type":"LOGOUT"}
```

发送成功回显只说明服务端完成当次转发写入，不保证接收者已读。断线边缘可能交付不确定；MVP 无消息补发、自动重试、历史保存和心跳。
