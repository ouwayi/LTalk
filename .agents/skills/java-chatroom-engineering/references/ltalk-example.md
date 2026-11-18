# LTalk 实现案例

这些类名和约束用于在 LTalk 中定位改动，也可作为查找其他项目对应层的参考。其他项目以本身的代码和构建配置为准。

## 定位入口与实现边界

LTalk 使用 Java 17 编译目标、Maven、Swing、Gson 和 SQLite JDBC。聊天走阻塞 TCP，按 UTF-8 编码的 LF 分隔 JSON 帧收发。局域网发现用 UDP，公网连接可启用 TLS，文件与原图在独立连接上传输原始字节。

| 查看任务 | 代码入口与改动时要保持的约束 |
| --- | --- |
| 建连、会话与慢端 | `server.ChatServer` 接入连接，`ClientSession` 持有 Socket 和心跳，`SessionRegistry` 管理名称预留、就绪和移除，`SessionOutput` 串行发送并限制积压。业务投递只面向就绪会话，`LOGIN_OK` 先于业务事件；慢连接的清理应局限于该连接。 |
| 帧和身份 | `common.MessageCodec`、`MessageType`、`Validation` 定义编码和校验。普通帧上限 16 KiB，指定头像帧 176 KiB，正文上限 2000 个 UTF-16 单元。身份绑定服务端认证连接；`requestId` 关联响应，`messageId` 由服务端生成。`common.Heartbeat` 校验当前探测的 PONG 编号。 |
| 消息、权限和历史 | `server.MessageRouter` 处理聊天、历史和社交请求，`SocialStore` 管理好友、私聊许可和群成员，`AccountStore` 管理账号。公共消息发给就绪会话；独立群发给就绪的当前成员；好友可离线留言，陌生人须获私聊许可且在线。`ChatArchive` 和 `SqliteDatabase` 承担授权归档和事务，私聊正文与双方可见记录一起提交。 |
| 附件和撤回 | `client.AttachmentChannel` 与 `server.AttachmentStore` 管理独立传输及票据。附件下载由本人历史可见范围授权。撤回由服务端核对发送者和 60 秒时限，归档原位置保留撤回提示；后续搜索跳过该条，停止授权新下载。 |
| 桌面与恢复 | `client.ChatClient`、`ClientSessions` 管理后台网络和会话交接，`ChatWindow` 负责窗口交互，`client.ui.ConversationStore` 保存展示状态。Swing 更新在 EDT。手动重连同一服务器并重新登录同一账号后，可恢复窗口内所选会话与草稿。发送确认由匹配的服务端回显驱动。 |

## 规格示例 · 从群成员名单转让管理员

**GROUP-01**。管理员希望在名单里直接挑选接任者。以管理员已打开独立群信息卡片为前提，拆为分页加载、按用户名片段筛选和确认转让三个操作。保留现有 `GROUP_MEMBERS` 和 `GROUP_TRANSFER` 请求。检查 `ChatWindow` 和 `GroupTransferDialog` 的界面流程；由服务端在提交时核对当前管理员和目标仍为成员。

用超出一页的群成员夹具验收。按用户名片段定位下一页的离线成员；选中另一成员后让其退群，等名单刷新并验证提交停用；另选一人后取消，核对管理员仍可打开转让窗口；最后确认转给离线成员，从群资料核对管理员已变更。相关入口为 `ChatWindowTest#administratorCanBrowseAllRosterPagesSearchAndTransferToAnOfflineMember` 和 `SocialIntegrationTest`。

## 选择回归入口

- 消息边界和多方投递用 `MessageCodecTest`、`ChatServerTest`。静默失联和慢端隔离用 `HeartbeatIntegrationTest`、`PublicCapacityTest`；TLS 校验用 `PublicConnectionTest`。
- 归档隔离、事务回滚和重启用 `HistoryPersistenceTest`、`SqlitePersistenceTest`；附件及撤回用 `AttachmentTransferTest`、`MessageRecallTest`。
- 在 LTalk 根目录使用 `mvn "-Dtest=MessageCodecTest,ChatServerTest" test` 跑专项，使用 `mvn package` 跑完整验证。图形用例需要桌面环境，网络用例需要回环 TCP。在 Windows 上也可用项目自带的 `build.ps1` 替代 `mvn`。
