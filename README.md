# LTalk 局域网聊天室

按《局域网聊天项目任务拆解》实现的 Java 课程项目 MVP。一个 TCP 服务端、多个 Swing 桌面客户端，无数据库、无需配置账号。

继续开发前请先读 [当前状态与交接](docs/STATUS.md)，其中记录代码基线、验证范围、已知问题和下一步建议；每完成一个开发阶段同步更新。协议、设计与历史验证证据仍保留在各自文档中。

已实现：多人群聊（含发送者回显）、服务端中转私聊（只发双方）、在线列表、唯一名称登录、输入与协议校验、主动退出、异常断线清理、双向心跳与失联检测、手动重连。网络连接、收发均在后台，界面在 Swing EDT 更新。私聊对象离线时保留草稿并禁用发送，必须明确重新选择目标。

## 环境与构建

- 技术栈：Java 单模块 Maven 工程；`Socket/ServerSocket` 阻塞 TCP；UTF-8、LF 分隔的 JSON 帧；Swing/Java2D 桌面 UI；`java.util.logging` 日志，无 Web 前端框架或数据库。
- **JDK 17 或更高版本**，须包含 `java`、`javac`；客户端需要图形桌面环境。
- **Maven 3.9.x**（Windows 可使用下面的脚本自动下载固定的 3.9.9）。
- 固定依赖：Gson **2.11.0**、JUnit Jupiter **5.11.4**（仅测试）。
- 实际验证环境：Windows、JDK **25.0.4**、Maven **3.9.9**，以 `--release 17` 编译；没有在 JDK 17 运行时实测。

构建前确认 `java -version` 与 `javac -version` 均可运行，并将 `JAVA_HOME`（如设置）指向 JDK。运行已打包 JAR 只需 Java 运行环境；编译和测试需要完整 JDK。其他操作系统可使用已安装 Maven，尚无独立运行验收记录。

在项目根目录运行：

```powershell
# Windows PowerShell：自动准备 Maven，构建并执行所有测试
powershell -NoProfile -File .\build.ps1

# 只运行测试
powershell -NoProfile -File .\build.ps1 test

# 依赖已经缓存后，可离线构建并测试
powershell -NoProfile -File .\build.ps1 -o package
```

脚本首次运行需要联网，将校验过的 Maven 和所有依赖放在 `.tools/`；不会将工具、缓存、JAR 提交到 Git。如果本机脚本执行策略阻止运行，可使用已安装 Maven 的通用命令：

```sh
mvn clean package
mvn test
```

构建产物 `target/ltalk-1.0.0.jar` **已包含运行依赖**。复制这个 JAR 到其他装有 Java 17+ 的设备即可运行，无需再安装 Maven。不要使用 `original-ltalk-1.0.0.jar`。

## 启动与操作

先启动服务端：

```sh
java -jar target/ltalk-1.0.0.jar
# 可选参数：端口、监听 IP、最大连接数（包含未登录连接）
java -jar target/ltalk-1.0.0.jar 9000 0.0.0.0 20
```

默认监听所有网卡的 TCP **9000** 端口、最多 **20** 个连接；按 Ctrl+C 关闭。未完成登录的连接在 **10 秒**绝对期限后关闭，正常登录用户不会因聊天空闲被断开。

登录后两端独立探测：默认等待 **10 秒**发送 PING，收到匹配 PONG 后等待下一轮，响应超时 **20 秒**。持续静默失联按本端配置在间隔 + 响应超时 + 调度容差内断开（默认约 30 秒加容差）；界面提示手动重连，服务端清理同名与连接名额。正常聊天空闲时自动响应心跳。服务端和客户端须一起更新到支持心跳的版本。

如需调整，在服务端与客户端的 `java` 后、`-jar`/`-cp` 前添加 JVM 参数，例如：

```sh
java -Dltalk.heartbeat.intervalMillis=5000 -Dltalk.heartbeat.timeoutMillis=15000 -jar target/ltalk-1.0.0.jar
java -Dltalk.heartbeat.intervalMillis=5000 -Dltalk.heartbeat.timeoutMillis=15000 -cp target/ltalk-1.0.0.jar cn.ltalk.client.ChatWindow
```

两项均为正整数毫秒，两端可独立配置。计时包含排队与写入等待；过短设置易受系统暂停、调度延迟或网络拥塞影响。具体帧和超时语义见 [通信协议](docs/protocol.md)。

如需显示接受连接、请求类型和路由错误等调试日志（不记录聊天正文）：

```sh
java -Djava.util.logging.config.file=docs/logging.properties -jar target/ltalk-1.0.0.jar
```

分别在三个终端启动客户端，也可只启动一个后手动填写连接信息：

```sh
java -cp target/ltalk-1.0.0.jar cn.ltalk.client.ChatWindow 127.0.0.1 9000 alice
java -cp target/ltalk-1.0.0.jar cn.ltalk.client.ChatWindow 127.0.0.1 9000 bob
java -cp target/ltalk-1.0.0.jar cn.ltalk.client.ChatWindow 127.0.0.1 9000 carol
```

1. 输入 IP、端口和用户标识，点击「连接」；收到登录成功后才启用发送。
2. 默认发送到公共聊天室；输入纯文本后点击蓝色发送按钮或按 **Enter**（兼容 **Ctrl+Enter**），**Shift+Enter** 换行。
3. 在左侧会话列表选择联系人进入私聊，标题栏显示当前对象；选择「公共聊天室」明确切回群聊。搜索框按名称与最后一条消息摘要筛选，不改变当前发送对象。
4. 成功提交发送队列后清空输入；正式聊天记录只显示服务端回显的一份消息。队列提交不等于已确认送达，错误会显示在状态区和消息区，不自动重发。
5. 左上菜单可打开连接设置或断开连接；关闭窗口也会退出。检测到服务端停止/连接故障后可手动重新连接。

界面采用分栏式会话列表、生成式字母头像、在线标记、未读徽标和左右消息气泡。聊天背景仅使用纯色或渐变，不包含纹样。会话各自保存本次窗口内的消息与草稿；切换登录名称会清除上一身份的界面历史。窗口窄于 760 像素时改为单列，点击会话进入聊天页，左上返回箭头回到列表。当前会话的放大镜可搜索已接收消息。

左上菜单或聊天区更多菜单中的「外观设置」支持浅色/深色、纯色/渐变、预设配色与自定义 `#RRGGBB` 色值，修改时可预览，点击「应用设置」后生效；取消不会改变当前外观。主菜单也可一键切换浅色/深色。两个模式分别保留所选颜色，设置保存在当前系统用户目录的 `.ltalk/appearance.properties`，重新启动后加载；该文件只包含外观参数，不保存聊天、用户标识或服务端地址。配置无效时回退到默认外观；保存失败会提示，但当前已应用的外观与草稿保留。

主菜单、新建会话和聊天更多菜单共用圆角、图标、悬停与分隔线样式，并跟随当前窗口的显示模式。切换主题不会断开网络连接，也不会改变当前会话、历史或草稿。

附件、表情面板、好友与新群组入口为明确标注的占位功能，不发送额外协议消息。现阶段仍是一个公共群聊和已有私聊。UI 结构、完整改动文件与后续接入位置见 [UI 设计与扩展说明](docs/ui-design.md)。

名称去首尾空白后为 1～20 位英文字母、数字或下划线，区分大小写；重名拒绝新连接。正文不能全为空白，最长 2000 个 Java `char` 单元（常见 emoji 占两个）；帧同时受 16 KiB UTF-8 限制。

## 局域网使用

服务端用 `0.0.0.0` 或本机网卡地址监听。在服务端运行 `ipconfig`，找到同一局域网的 IPv4，例如 `192.168.1.20`。**其他设备的客户端填服务端 IPv4，不能填 `127.0.0.1` 或 `0.0.0.0`。**

仅在需要的网络配置下允许 Java 或指定 TCP 端口入站；无需关闭整机防火墙。确认设备接入同一可互访网络、无线 AP 未启用客户端隔离。远端设备可用 Windows `Test-NetConnection <服务端IP> -Port 9000` 排查连通性。

用户已完成心跳加入前的**宿主机＋虚拟机跨系统测试**：宿主机运行服务端和 alice，虚拟机运行 bob、carol，基本功能反馈正常；双物理设备未测。此次心跳已通过本机单向丢包代理测试，虚拟机断网场景尚待复测。检测到失联后手动连接；尚在检测窗口内也可先点击“断开”再“连接”。实际过程与验证范围见 [测试记录与验收清单](docs/test-report.md)。

## 测试

日常回归使用上面的 `build.ps1 test` / `mvn test`；交付打包使用 `build.ps1` / `mvn clean package`。2026-10-27 心跳阶段执行离线完整构建，**54 项测试通过，0 失败、0 错误、0 跳过**；包含本机真实 Socket 心跳/聊天集成测试与 Swing EDT 组件测试。运行测试需要允许本机 TCP，完整 UI 回归需图形桌面环境。结果与未验证范围见 [当前状态](docs/STATUS.md) 和 [测试报告](docs/test-report.md)。

```powershell
# Windows：10 个真实 Socket 客户端持续 10 分钟
powershell -NoProfile -File .\build.ps1 '-Dtest=LoadTest' '-Dltalk.soak.seconds=600' test
```

```sh
# 已安装 Maven 的等价命令
mvn -Dtest=LoadTest -Dltalk.soak.seconds=600 test
```

常规测试包含 2 秒的 10 客户端短回归；上面的命令单独执行 10 分钟观察。JUnit 报告位于 `target/surefire-reports/`（后续运行会覆盖）。该测试使用独立测试进程中的临时端口，只验证本机 TCP，不代替多机测试。源码覆盖帧边界、中文/emoji/换行、错误类型、身份绑定、并发注册、群聊回显、私聊隔离、退出/重连、登录期限、连接上限、旧回调和列表版本。

## 文档与结构

```text
src/main/java/cn/ltalk/
  common/   消息模型、Gson JSON 编解码、有限长读取、公共校验
  server/   监听、连接会话、注册表、消息路由
  client/   异步网络客户端、事件接口、Swing 窗口适配层
    ui/     会话状态、列表、消息气泡、主题与窄屏布局
src/test/java/cn/ltalk/  单元测试、真实 TCP 集成测试、负载观察
docs/                   需求、协议、设计、测试与演示
```

- [需求与用户流程](docs/requirements.md)
- [当前状态与开发交接](docs/STATUS.md)
- [通信协议与错误码](docs/protocol.md)
- [架构、线程与关键设计](docs/design.md)
- [测试结果、证据与待验收项](docs/test-report.md)
- [课程报告与演示脚本](docs/course-report.md)
- [桌面 UI 设计、改动清单与扩展接入位置](docs/ui-design.md)

## 常见问题与边界

| 现象 | 处理 |
| --- | --- |
| 服务端启动失败/Address already in use | 检查端口占用，或换端口并同步修改客户端配置 |
| 连接被拒绝或超时 | 确认服务端已启动、IP/端口正确；检查防火墙和设备互访 |
| USER_EXISTS | 更换名称，或等待旧连接被服务端检测清理后重试 |
| USER_OFFLINE | 目标已离线，重新选择当前在线用户 |
| 超过连接上限后立即断开 | 默认包含未登录连接共 20 个，退出部分连接或调整启动参数 |
| 桌面客户端启动失败 | 需要图形桌面；服务器无桌面时仅运行服务端 |

这是可信课程局域网的演示实现：无身份认证、无 TLS、无聊天历史、无离线消息、无自动重连或已读回执。服务端生成发送者和时间，不采信请求伪造的 `from`。心跳提供有期限的探测，不承诺硬实时检测或消息送达；同步广播对不读取数据的慢客户端仍可能发生写入等待，尚无通用慢客户端输出队列与淘汰策略；目标规模为 5～10 个正常客户端。
