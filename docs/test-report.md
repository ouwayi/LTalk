# 测试报告与验收记录

## 环境与总体结果

- 日期：**2026-10-24（Asia/Shanghai）**。
- 系统：Windows；JDK **25.0.4**，Maven **3.9.9**，源码/目标兼容 **Java 17**。
- 网络：自动化均使用 `127.0.0.1` 和系统分配的临时 TCP 端口；未使用第二台设备。
- 最终完整构建：**28 tests, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS**。
- 10 客户端独立长测：**600 轮，600.49 秒，6,000 次群聊投递、1,200 次私聊投递，0 失败**。
- Swing 自有组件树在 EDT 上验证通过，并生成组件渲染预览；**不等同于人工拖动/点击窗口、多机实测**。

最终构建实际命令：

```powershell
powershell -NoProfile -File .\build.ps1
```

也已验证 `-o package` 离线构建（仅用于依赖已下载后）。默认命令输出摘要（21:29:19）：

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 7.344 s
```

| 测试类 | 测试数 | 核心行为 |
| --- | ---: | --- |
| MessageCodecTest | 6 | Unicode/转义换行、帧边界、严格类型、16 KiB、残帧、char 边界 |
| ChatServerTest | 12 | 群聊、私聊隔离、身份、并发注册、非法请求、退出、超时、连接上限、并发收发 |
| ChatClientTest | 6 | 客户端端到端、登录失败、连接失败/超时、版本、旧回调隔离、重连和线程释放 |
| ConversationSelectionTest | 2 | 私聊目标失效保护、自选群聊、禁止自己/离线目标 |
| ChatWindowTest | 1 | EDT 组件：输入错误、连接、私聊、目标离线、草稿、明确切群聊、单次回显、断开 |
| LoadTest | 1 | 常规 2 秒、10 客户端回归；可配置为 600 秒长测 |

可复现的生成证据（不纳入 Git）：

- `target/surefire-reports/*.txt` 和 `TEST-*.xml`：最近一次 JUnit 报告，后续测试会覆盖。
- `target/ui-preview/chat-window.png`：ChatWindowTest 渲染的组件预览，非桌面截图。
- 本次本地 `.tools/final-build.log`：最终完整构建日志；`.tools/soak-run.log`：10 分钟运行日志。
- 以上工具、缓存、日志、生成物均被忽略；本报告保存关键结果和复现命令。

附加交付验证：在新的临时目录下载 Maven 3.9.9、核对固定 SHA-512 并启动成功；构建脚本的零参数默认 package、单参数 `-v` 和多参数模式均已检查。最终 JAR 通过独立子进程启动验证，监听 `0.0.0.0`、LOGIN_OK 首帧、中文/emoji 群聊回显及 LOGOUT 后 EOF 均通过（`PACKAGED_SMOKE_PASS`），不依赖 Maven 测试类路径。

## 10 分钟观察

运行时间：**21:10:16～21:20:17**。10 个轻量 TCP 客户端 `load0`～`load9`，每秒轮换一个用户发一条群聊及一条私聊；每轮检查群聊全部收件人、私聊双方及在线数量；收到的私聊帧必须以本用户为发送者或接收者。独立测试 JVM 内启动服务端。

复现：

```powershell
powershell -NoProfile -File .\build.ps1 '-Dtest=LoadTest' '-Dltalk.soak.seconds=600' test
```

实际摘要：

```text
LOAD_RESULT clients=10 rounds=600 groupDeliveries=6000 privateDeliveries=1200
elapsedSeconds=600.49 maxRoundTripMillis=9
baselineThreads=0 peakThreads=12 finalThreads=0
heapBeforeMiB=31.07 peakHeapMiB=105.42 heapAfterMiB=105.84
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

观测到无崩溃、消息断帧、缺失或重复回显；本机单轮收发最大耗时 9 ms，非网络 SLA。关闭后相关线程由 12 回到 0。堆数值包含测试探针保存的全部收包对象及 JVM 未回收分配，且没有强制 GC；**本记录不据此宣称无堆增长或证明不存在内存泄漏**，也不代表生产级压力测试或慢客户端测试。

## 对照原文 Q01～Q18

| 编号 | 状态 | 实际验证/证据 |
| --- | --- | --- |
| Q01 三人连接 | 自动化通过；三桌面人工未验证 | alice/bob/carol 在线列表一致；groupEchoPrivateIsolationAndNoHistory |
| Q02 群聊 | 通过 | 中文/emoji/转义换行，三端各一次；MessageCodecTest、ChatServerTest、组件测试 |
| Q03 私聊隔离 | 通过 | A/B 各一次，C 实际 Socket 收包无私聊；第三方 SYSTEM/列表无正文；双向私聊 |
| Q04 切换目标 | 组件测试通过；人工未验证 | Swing 私聊 bob → bob 离线禁发 → 明确切群聊 → 单次群聊回显 |
| Q05 正常上下线 | 通过 | LOGOUT、Socket.close、10 轮同名上下线；组件断开与客户端重连 |
| Q06 重名/并发注册 | 通过 | 原 alice 存活；同时抢同名一个 LOGIN_OK、一个 ERROR；LOGIN_OK 首帧 |
| Q07 非法/离线目标 | 通过 | USER_OFFLINE、SELF_PRIVATE_NOT_ALLOWED、缺 to；组件保留草稿不自动群发 |
| Q08 输入边界 | 通过 | 空白/2000/2001 char，中文、emoji 占两个 char；非法/超长用户名 |
| Q09 协议/身份 | 通过 | 非法 JSON、未知类型、缺字段、类型错误、登录前聊天、重复登录、伪造 from/time、超长 requestId |
| Q10 TCP 帧 | 通过 | 真实 Socket 拆分 LOGIN、合并两帧聊天；字节流单字节 UTF-8 分片 |
| Q11 超帧/登录超时 | 通过 | 不换行的第 16385 字节立即拒绝；短配置绝对登录期限；持续滴入字节也不能延长；名额回收 |
| Q12 异常结束 | Socket/RST 自动化通过；人工杀进程未验证 | SO_LINGER=0 模拟 RST，旧会话清理、同名重连，其他连接仍能聊天 |
| Q13 服务端/连接失败 | 部分通过 | 停止服务端、恢复连接、拒绝端口、端口占用、无登录响应超时；真实不可达 IP 和窗口拖动未人工验证 |
| Q14 并发/重复释放 | 通过 | 三用户同时各发 20 条，每端 60 个唯一事件；10 轮连接退出；旧 EDT 队列回调隔离；线程释放 |
| Q15 基本负载 | 已执行 10 分钟，边界见上 | 10 客户端、600 轮、7200 次投递；资源与时延摘要如上，未对慢连接或堆稳定性作保证 |
| Q16 两设备局域网 | **未验证** | 无可控制的第二台设备；不能用本机测试替代 |
| Q17 静默断网 | **未验证实际拔线**；已记录边界 | 无心跳，静默黑洞不承诺固定期限检测，不用聊天空闲超时替代 |
| Q18 扩展 | 未实现，不在本 MVP 范围 | X1～X6 均未做 |

## 剩余人工与局域网验收

桌面自动化曾尝试连接已启动的项目窗口，工具返回 `Computer Use app approval timed out`，因此没有把它记为人工交互通过。后续组件测试直接测试项目自身的非可见 Swing 组件树，无 OS 输入自动化；组件状态与渲染通过。真实窗口拖动、三桌面操作和其他系统字体显示仍待人工确认。

请按以下表格在真实环境填写，完成前均保持“未验证”：

| 项目 | 当前记录 | 补测内容 |
| --- | --- | --- |
| 设备与地址 | 未验证 | 设备 A 系统/JDK/IPv4/端口；设备 B 系统/JDK/IPv4；A 放服务端+alice，B 放 bob/carol |
| 入站与互访 | 未验证 | 指定 TCP 端口规则/网络类型、客户端到服务器连通性，不关闭整机防火墙 |
| 远端群聊 | 未验证 | A 发 G-LAN-001，三端各一份；B 回复含中文换行 |
| 远端私聊 | 未验证 | alice ↔ bob；carol 收包层没有对应 PRIVATE_MESSAGE，保留收包记录/视频 |
| 上下线/强退/重连 | 未验证 | 关闭 bob 后列表更新，重启 bob 可加入；关闭服务端后三端提示断线 |
| 操作响应 | 未验证 | 连接/登录超时与持续收消息期间拖动、编辑窗口，不冻结 |
| 静默断网观察 | 未验证 | 拔线/关 Wi-Fi 时间、实际检测时间或“未检测”；恢复后手动重连 |
| 证据 | 未验证 | 为每项填通过/失败、截图或视频/收包记录路径、缺陷编号与回归结果 |

## 已处理问题与限制

开发验证已修正协议异常类型与 JDK 同名类的导入冲突、无效字段关联错误 requestId，以及无效超长 requestId 回显可能超帧的问题；最终回归 28 项通过。曾在受限环境运行 Socket 测试导致无法连通，随后在允许本机 TCP 的测试环境重新运行通过，没有修改或跳过失败断言。

仍存在的范围限制：无账号认证/TLS、持久化/离线私聊/自动重连/心跳；服务端同步写可能受慢客户端阻塞；聊天区历史随本次进程累积；没有在 JDK 17 运行时或其他系统桌面实测。课程“全部 P0 验收完成”应等待上述人工及多机证据补齐。
