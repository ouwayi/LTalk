# 测试报告与验收记录

## 当前最新回归（2026-11-01）

导出阶段完整 **81/0/0/0，约 1:12**；历史/删除阶段完整 **89/0/0/0，约 1:20**，日志 .tools/export-save-final.log / .tools/history-final.log。最终只将删除期间导出临时文件清理留在后台，相关 **14/0/0/0，22.449 秒**通过并重打包，日志 .tools/history-final-focused.log；不把这次定向回归称作再次全量 89 项。环境仍 Windows/JDK 25.0.4/Maven 3.9.9、release 17。

新增 HistoryExportTest 2 项、FileDialogsTest 1 项、HistoryPersistenceTest 4 项、HistoryClientTest 2 项、HistoryStoreTest 1 项、ChatWindowTest 1 项；旧 78 项核心断言保留。覆盖逐行标准 JSON、文件类型与扩展名/中文自定义名、不确认不覆盖/失败不改旧文件；历史加载、本人删除权限/他人字节不变、快照失效、重启持久化、并发删除投递顺序、超时取消与迟到页面、历史/实时重复与未读、离线会话恢复及确认框删除/清空后重登。新 UI 测试曾因 lambda 捕获变量重新赋值而编译失败，修正变量后运行，不删测或放宽。

原生隔离 SaveDialogPreview 确认现代保存窗口同窗包含文件名/类型，JSON/CSV 可切换、建议扩展名随类型更新。首轮仅 UIA SetValue 改显示未提交，返回默认名；后实际键盘输入中文/空格自定义文件名和目录，返回一致路径/JSON 类型，未写文件。历史恢复组件图也已检查。不是用户手动的完整账号/删除/导出或多机验收；其他系统、完整 DPI、独立 JDK 17 与长期堆/慢端专项仍未完成。


## 历史回归（2026-10-31）

最终 `build.ps1 -o package`：**78/0/0/0，BUILD SUCCESS，约 1 分 12 秒**，日志 `.tools/account-handoff-final.log`；环境 Windows/JDK 25.0.4/Maven 3.9.9、release 17。阶段 14 完整 74 项（约 1:11）与阶段 15 专项 9 项（20.924 秒）均通过；旧日期结果不是今日证据。

新增 FileDialogsTest、AuthenticationPanelTest、ClientSessionsTest 3 项及 ChatWindowTest 1 项。核心覆盖：选择回调成功/取消/失败和 EDT；密码文本/光标不变、独立显隐与重置；旧账号在切换页继续收消息/保留草稿，错密码/取消/认证中返回不退出旧账号，成功后旧用户离线/新身份路由；连接已满保留旧账号，关闭释放，两服务端同名也交接并触发旧展示清理。首次专项两处失败是测试等待条件/观察者遗留回显准备问题，修正为精确身份/消息后原行为断言通过，未删测/跳过/放宽。

computer-use 隔离窗口观察 Windows 图片与导出目录的现代原生导航/地址/搜索、图片筛选、图片取消；后续无父窗预览实际选择并完整返回 target/ui-preview 目录和含中文/空格的测试图片路径。仅测试合成图，不接触真实账号/照片。确认最终登录页和成功切换组件预览。原生路径验证是单独选择器，并非完整新账号/头像/导出用户手动端到端验收；多机、完整 DPI/其他系统、独立 JDK 17、长期堆观察仍未做。


## 历史结果（2026-10-30）

阶段 12（cdf5927）完整离线构建 **63/0/0/0，57.306 秒**，会话导出与入口精简；阶段 13 完整 `build.ps1 -o package` **72/0/0/0，BUILD SUCCESS，约 1 分 5 秒**，日志 `.tools/account-workspace-final.log`。环境 Windows/JDK 25.0.4/Maven 3.9.9、release 17。首次沙箱网络/原子移动受限的阶段 12 回归在授权环境保留原断言重跑，不删测、不放宽断言。

完整回归后仅调整深色注册复选框文字及资料时间展示，再执行 `-Dtest=ChatWindowTest,AppearancePanelTest package`：**7/0/0/0，13.359 秒**，重新打包；日志 `.tools/account-workspace-ui-final.log`。不把定向 7 项描述成再次全量 72 项。

| 新增/更新测试 | 本轮行为证据 |
| --- | --- |
| AuthenticationExportTest（阶段 12 新增 2 项） | 服务端群/双向私聊/空会话筛选、他人会话拒绝、游标范围绑定、客户端 JSON/CSV 内容 |
| ProfileConnectionTest（新增 4 项） | HELLO 后未认证拒绝业务且心跳保活；错密码重试；资料仅本人、持久化/重启/恢复头像、旧账号兼容、保存失败保留原资料及密码、图片/字段类型限值 |
| AccountFlowTest（新增 4 项） | 握手与登录分离、手填/随机注册自动登录、重名不误登录、资料更新/重登、握手/认证超时关闭、资料超时忽略迟到回复且聊天仍可用 |
| ChatWindowTest（新增 1 项、原 4 项保留） | 自动连接启动页、菜单迁移、登录/注册和密码确认、资料编辑确认后更新栏、退出/切换账号清理、窄屏滚到提交按钮、深色文字；原聊天/外观/键盘/未读/草稿/离线等回归 |
| ChatClientTest（更新原测试） | 补查旧连接已排队的资料回调不污染新身份，原状态与列表隔离断言保留 |

已人工查看自动化生成的组件渲染图：welcome-login/register/dark/narrow/narrow-bottom、profile-editor/footer，以及原聊天桌面/窄屏预览；发现并修复启动页裁切、成功登录后残留认证错误和深色复选框文字对比度。自动化曾显示个人信息窗口并操作自有 Swing 控件，**不是用户手动原生验收**。本轮未做多机、独立 JDK 17、完整高 DPI、文件选择器手动上传、长时压力或全链路磁盘故障恢复。新流程待用户人工验收；下列 2026-10-29 及更早结果保持历史归属。


## 账号认证与记录导出（2026-10-29）

Windows/JDK 25.0.4、Maven 3.9.9，release 17。`powershell -NoProfile -File .\build.ps1 -o package`：**61 项、0 失败/错误/跳过，BUILD SUCCESS，57.236 秒**，含依赖 JAR 生成；日志 `.tools/auth-export-final.log`，JUnit 在 `target/surefire-reports/`（可覆盖）。原 54 项全部保留，将测试账号显式注册/预置并携带密码，未放宽旧行为断言。首轮两处 UI 测试缺密码而失败，补充登录准备后通过；工具链/打包警告仍保留。

新增 AuthenticationExportTest 7 项覆盖：必填/格式/密码长度、随机用户名、并发重名唯一成功、盐哈希不含明文、重启验证、Windows 大小写账户及历史隔离、未认证导出、其他用户/伪造游标拒绝、登出关闭、错误密码统一提示；真实 TCP 三端导出私聊隔离、空 JSON/CSV、引号/换行/emoji、文件命名、归档重启、400 条稳定快照、10000 条流式文件写出后流式解析、取消删除临时文件、损坏历史/无效目录/目录锁冲突错误。客户端文件失败保持健康聊天连接。不是十万用户负载或磁盘故障事务证明。

未验证：本轮注册/导出原生弹窗、多机账号流程、独立 JDK 17 运行时、长期堆稳定性/慢客户端与磁盘故障恢复。昵称目前保存但列表仍使用用户名；只归档在线期间授权接收的消息，无离线投递/历史回放/TLS。此前各阶段测试数字为当时记录，不倒填为本轮新增成果。

## 虚拟机心跳断网反馈补充（收到反馈：2026-10-28）

用户在本对话补充：已测试虚拟机断网检测，功能正常，断网一段时间后可以自动检测到连接断开。记为**用户实测反馈正常**，补充下列心跳阶段当时尚未进行的虚拟机观察；本轮代理仅核对和更新文档，没有重做实验或重跑自动化。

实际测试日期、被测提交/JAR、心跳配置、系统/JDK、虚拟网络模式、检测端、精确耗时及截图/日志未提供；收到反馈日期不代替测试日期。“一段时间”不作为固定 30 秒达标证据，也不据此推定服务端名称/名额回收、完整界面交互或恢复后同名重连已获人工验证，这些核心行为另有本机自动化证据。旧版 Q17 现象保留历史语境，演变见 [开发日志](DEVLOG.md)阶段 2、7、9。

## X1 心跳阶段（2026-10-27）

Windows/JDK 25.0.4/Maven 3.9.9，Java `release 17`。`powershell -NoProfile -File .\build.ps1 -o package`：**54 项，0 失败、0 错误、0 跳过，BUILD SUCCESS，17.635 秒**。日志 `.tools/heartbeat-build.log`，生成含依赖 JAR；未修改 UI，原有 46 项回归保持。JUnit 报告仍在 `target/surefire-reports/`，后续专项运行会覆盖相应报告。

新增 HeartbeatIntegrationTest 6 项、HeartbeatSettingsTest 2 项：

| 核心行为 | 实际验证 |
| --- | --- |
| 正常空闲 | 两个真实 ChatClient 在不同本端配置下空闲 1.5 秒，跨过短测试配置的多轮期限仍在线，然后群聊与私聊通过；心跳不进入业务回调 |
| 静默/单向失联 | 本机 TCP 代理保持两条 Socket 打开，不向另一端转发 FIN/RST，分别丢弃 C→S 和 S→C 字节；间隔 80 ms、响应 500 ms，两端主动超时，测试要求完成清理耗时小于 2500 ms（含测试/调度容差），非 EOF 导致的断开 |
| 状态/列表/重连 | 正常观察者收到递增版本的离线列表且仍在线；失联客户端禁发，可在服务端清理后用同名直连；超过旧期限后新连接仍可发消息 |
| 关联与错误 | 错误 PONG、普通服务端事件或来自对方的 PING 不延长本端期限；服务端拒绝未登录心跳及缺失/空白/超长的 PING/PONG 编号；合法 PING 原编号得到 PONG |
| 帧与资源 | 不完整 PONG 卡住读取时仍被独立 watchdog 关闭；失联名称/连接名额回收；5 轮连接/心跳/断开后无遗留 heartbeat 线程 |
| 配置与编码 | 默认 10000/20000 ms、显式系统属性、无效值/边界拒绝；PING/PONG 带编号编解码一致 |

另外执行 `powershell -NoProfile -File .\build.ps1 -o '-Dtest=LoadTest' '-Dltalk.soak.seconds=35' test`，默认心跳配置下 10 客户端 **35.34 秒、35 轮、350 群聊 + 70 私聊投递**通过；本机最大单轮 4 ms，线程 0 → 峰值 32 → 0。日志 `.tools/heartbeat-soak.log`；测试 Peer 在读取时自动回应 PING，未改变业务断言。此观察跨过默认探测周期，不替代历史 600 秒长测，也不证明堆稳定性。

首次沙箱内专项运行因 `Permission denied: connect` 失败；允许本机 TCP 后原断言重跑 8 项通过，后续完整回归通过。尝试离线 `clean package` 时 clean 插件未缓存，未能执行 clean，改用已缓存插件的 `-o package` 完成编译、全测与打包，没有删除/跳过测试。既有 JDK 25 native-access/Unsafe 及 Shade 资源重叠警告仍保留。

**当时未验证**：新版本的虚拟机断网、真实窗口心跳超时展示、双物理设备、独立 JDK 17 运行时、慢客户端专项与长期堆稳定性。虚拟机断网检测随后获得用户正常反馈，见开头补充；其余无新增验证记录。心跳无自动重连/重发；下文无心跳的 Q17 仅代表旧版历史观察。

## 交接核对回归（2026-10-27）

本阶段仅整理文档，未修改业务源码；重新执行 `powershell -NoProfile -File .\build.ps1 -o package`，Windows/JDK 25.0.4/Maven 3.9.9 下 **46 项测试通过，0 失败、0 错误、0 跳过，BUILD SUCCESS**，耗时 13.821 秒。包括默认 2 秒、10 客户端短回归，没有重新执行 600 秒长测、真实窗口操作或虚拟机断网实验。

日志 `.tools/handoff-build.log`，JUnit XML 位于 `target/surefire-reports/`，均为可覆盖的本机生成物。存在 JDK 25 工具链 native-access/Unsafe 与重复 package 时的 Shade 类/资源重叠警告，本次未改依赖或打包配置。当前状态与待办以 [STATUS.md](STATUS.md) 为入口；下文保留各阶段历史验证来源与范围。

## 环境与总体结果

- 初始 MVP 自动化构建日期：**2026-10-24（Asia/Shanghai）**；UI 改造回归：**2026-10-25**。宿主机＋虚拟机人工验收由用户执行并反馈，具体测试时间未提供。
- 系统：Windows；JDK **25.0.4**，Maven **3.9.9**，源码/目标兼容 **Java 17**。
- 网络：自动化使用 `127.0.0.1` 和系统分配的临时 TCP 端口；后续用户完成宿主机＋虚拟机跨系统人工测试，宿主机运行服务端和 alice，虚拟机运行 bob、carol。
- 初始 MVP 完整构建为 28 项、分栏界面回归为 35 项、外观菜单为 46 项；当前心跳扩展后：**54 tests, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS**。
- 10 客户端独立长测：**600 轮，600.49 秒，6,000 次群聊投递、1,200 次私聊投递，0 失败**。
- Swing 自有组件树在 EDT 上验证通过，并生成组件渲染预览；用户随后反馈已完成待人工验收项目，除静默断网检测限制外均可正常运作。人工结果来源为用户反馈，未附截图或收包日志；不将此记录写成双物理设备测试。

## 初始 MVP 构建证据（2026-10-24）

初始构建实际命令：

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

## 分栏界面回归（2026-10-25，35 项基线）

本次仅更新 Swing 展示和本地会话状态。差异检查确认 `server/`、`common/`、ChatClient、ClientEventListener 与 pom.xml 没有变化，后端接口及 TCP 协议保持不变。原有网络与协议测试全部保留。

最终命令：`powershell -NoProfile -File .\build.ps1 -o package`（依赖已缓存）。本地完整输出位于 `.tools/ui-final-build.log`，最近一次 JUnit 报告位于 `target/surefire-reports/`。

```text
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 当前测试数 | 改造后的验证内容 |
| --- | ---: | --- |
| MessageCodecTest / ChatServerTest / ChatClientTest | 6 / 12 / 6 | 原有编解码、身份、并发、收件人、私聊隔离、超时与重连回归 |
| ConversationSelectionTest | 2 | 保留既有离线目标保护回归 |
| ChatWindowTest | 2 | 实际 Socket＋EDT：会话分组、原有错误与离线保护、草稿切换、Enter/Shift+Enter、搜索、未读、窄屏导航、工具占位与预览 |
| ConversationStoreTest | 4 | 群聊/私聊与回显分组、后台未读、离线后必须重选、草稿及登录身份隔离、搜索 |
| MessageTimelineTest | 2 | HTML 字面值保持纯文本、长单词换行、消息可选择、滚动到最新位置、相同内容事件不丢失及身份切换 |
| LoadTest | 1 | 原有 10 客户端短回归，2 秒；本次没有重跑前一天的 10 分钟观察 |

发现并修正富文本消息的连续长单词可能水平溢出的问题：允许字形视图在气泡宽度内换行。新增测试保留 1900 字符连续英文串与中文、emoji、换行和 HTML 字面文本，检查正文未被解释、气泡宽度受限、正文高度增长和滚动位置，没有删除或跳过断言。

组件预览已逐张检查：`target/ui-preview/ui-desktop.png`（1200×780）、`ui-narrow-chat.png`（420×760）、`ui-narrow-list.png`（420×760）。均为自动化测试数据渲染，生成物不纳入 Git。

### 真实 Windows 窗口验收

使用 computer-use 技能在独立本机测试端口 39003 启动服务端、bob/carol 测试参与者和新版 alice 窗口；未接入用户原有聊天室。窗口访问成功，本次没有遇到先前的授权超时。

| 操作 | 实际结果 |
| --- | --- |
| 点击连接 | 连接配置折叠；三人在线，消息按群聊和 carol 私聊分别显示 |
| 群聊输入并按 Enter | `UI-CUA-001` 只产生一份右侧气泡，输入清空；bob、carol 收包记录均有 GROUP_MESSAGE |
| 打开 carol 私聊 | 未读徽标清零，群聊历史不进入该私聊 |
| Shift+Enter 多行，再 Enter 发送 | `UI-CUA-002` 显示为一条多行气泡；只有 carol 的测试收包记录包含此 PRIVATE_MESSAGE |
| 将窗口从桌面宽度拖窄至约 469 像素 | 右侧改为全宽单列聊天，显示返回按钮，气泡和输入区没有水平溢出 |
| 点击返回、触发一条群聊新消息 | 显示全宽会话列表，公共聊天室出现未读红点与数字徽标 |
| 打开 bob 私聊并输入草稿，再关闭测试 bob 连接 | 当前页仍为 bob；显示离线重选提示，发送按钮禁用，`UI-CUA-003` 草稿保留，未变为群聊发送 |

测试收包和服务端日志分别位于 `.tools/ui-demo-frames.log`、`.tools/ui-demo-server.log`；测试 UI 的错误输出日志为空。原生窗口观察用于补充组件测试，不将本次本机运行写成新一轮虚拟机断网或双物理设备测试。

## 背景、主题与菜单回归（2026-10-25）

本轮移除背景纹样，增加纯色/渐变、自定义颜色、浅色/深色和本机偏好保存，并统一主菜单、新建会话和聊天更多菜单。服务端、common、ChatClient、ClientEventListener 和 pom.xml 未变；原 35 项测试保留，新增 11 项行为与错误路径检查。

命令：`powershell -NoProfile -File .\build.ps1 -o package`。本地完整日志 `.tools/appearance-build.log`，最近报告 `target/surefire-reports/`。

```text
Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 11.772 s
```

| 测试类 | 当前测试数 | 覆盖内容 |
| --- | ---: | --- |
| 原网络、协议、Store、Timeline、Selection 与 LoadTest | 33 | 原有业务回归，未删除或跳过断言 |
| ChatWindowTest | 4 | 原 2 项；新增切换后原消息换色、连接/草稿保留、重启加载外观、保存失败提示 |
| AppearanceSettingsTest | 2 | HEX 校验、透明色拒绝、不同模式颜色独立、纯色像素均匀及渐变端点 |
| AppearancePreferencesTest | 3 | 设置完整往返、损坏/超大/未知版本回退、写入失败不损坏目标且无临时文件残留 |
| AppearancePanelTest | 2 | 无效颜色阻止应用、深色/纯色预览、取消不发布、恢复默认 |
| ChatPopupMenuTest | 2 | 动作、禁用项、悬停颜色与窗口色板隔离 |

新增预览已检查：`target/ui-preview/ui-dark-desktop.png`、`ui-dark-narrow.png`、`menu-light.png`、`menu-dark.png`、`appearance-settings.png`。浅色/窄屏已有预览由本轮测试重新生成，均不提交 Git。

### 真实窗口补充验收

使用 computer-use 在本机独立端口 **39005** 验收，测试进程的 `user.home` 指向 `.tools/appearance-demo-home`，不覆盖实际系统用户的外观设置。

| 操作 | 实际结果 |
| --- | --- |
| 打开新建会话与主菜单 | 圆角、分组标题、图标、分隔线和行距正常，无旧式方框蓝边；Escape 可关闭菜单 |
| 主菜单一键切换深色 | 会话栏、连接表单、聊天背景、标题、输入与空状态即时换色；深色菜单文字和图标可读 |
| 打开外观设置 | 显示浅色/深色、纯色/渐变、预设、两个自定义色值和预览 |
| 选择纯色并输入 `#23384A`，点击应用 | 预览与聊天区变为选定纯色；配置记录 `mode=DARK`、`background=SOLID`、`dark.start=#23384A` |
| 深色下连接并接收消息 | 三人在线，群聊消息、系统提示和私聊未读正常显示 |
| Enter 发送 `APPEARANCE-001` | 显示一份右侧深色气泡，输入清空；bob、carol 的网络收包均包含对应 GROUP_MESSAGE |

本机日志为 `.tools/appearance-demo-frames.log`、`appearance-demo-server.log`、`appearance-demo-window.log`，UI 错误输出为空。本轮不重复前一天的 10 分钟长测，也不将本机 UI 验收写成虚拟机断网测试。

## 10 分钟观察（初始 MVP，2026-10-24）

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
| Q01 三人连接 | 自动化通过；人工通过（用户反馈） | 自动化三端列表一致；人工分布为宿主机服务端＋alice，虚拟机 bob＋carol |
| Q02 群聊 | 通过 | 中文/emoji/转义换行，三端各一次；MessageCodecTest、ChatServerTest、组件测试 |
| Q03 私聊隔离 | 通过 | A/B 各一次，C 实际 Socket 收包无私聊；第三方 SYSTEM/列表无正文；双向私聊 |
| Q04 切换目标 | 组件测试通过；人工通过（用户反馈） | 组件测试覆盖私聊 bob → bob 离线禁发 → 明确切群聊 → 单次群聊回显；用户反馈操作正常 |
| Q05 正常上下线 | 通过 | LOGOUT、Socket.close、10 轮同名上下线；组件断开与客户端重连 |
| Q06 重名/并发注册 | 通过 | 原 alice 存活；同时抢同名一个 LOGIN_OK、一个 ERROR；LOGIN_OK 首帧 |
| Q07 非法/离线目标 | 通过 | USER_OFFLINE、SELF_PRIVATE_NOT_ALLOWED、缺 to；组件保留草稿不自动群发 |
| Q08 输入边界 | 通过 | 空白/2000/2001 char，中文、emoji 占两个 char；非法/超长用户名 |
| Q09 协议/身份 | 通过 | 非法 JSON、未知类型、缺字段、类型错误、登录前聊天、重复登录、伪造 from/time、超长 requestId |
| Q10 TCP 帧 | 通过 | 真实 Socket 拆分 LOGIN、合并两帧聊天；字节流单字节 UTF-8 分片 |
| Q11 超帧/登录超时 | 通过 | 不换行的第 16385 字节立即拒绝；短配置绝对登录期限；持续滴入字节也不能延长；名额回收 |
| Q12 异常结束 | Socket/RST 自动化通过；人工通过（用户反馈） | 自动化模拟 RST 验证清理与重连；用户反馈已完成待人工项目且正常，未另附进程日志 |
| Q13 服务端/连接失败 | 自动化及人工通过（用户反馈） | 自动化覆盖停止服务端、恢复连接、拒绝端口、端口占用和无登录响应超时；人工补测反馈正常 |
| Q14 并发/重复释放 | 通过 | 三用户同时各发 20 条，每端 60 个唯一事件；10 轮连接退出；旧 EDT 队列回调隔离；线程释放 |
| Q15 基本负载 | 已执行 10 分钟，边界见上 | 10 客户端、600 轮、7200 次投递；资源与时延摘要如上，未对慢连接或堆稳定性作保证 |
| Q16 跨系统群聊和私聊 | 宿主机＋虚拟机通过（用户反馈） | 服务端与 alice 在宿主机，bob、carol 在虚拟机；群聊、私聊、上下线等反馈正常；未进行双物理设备测试 |
| Q17 静默断网 | 本机心跳自动化通过；用户补充虚拟机检测正常 | 旧版空闲 carol 保留旧状态为历史观察；新版本本机单向丢包两端超时/清理/重连通过；2026-10-28 收到用户虚拟机断网后自动检测断开的反馈，精确耗时与配置未提供 |
| Q18 扩展 | X1 已实现并经本机验证 | 双向心跳与可配置失联检测；X2～X6 未实现 |

## 宿主机与虚拟机人工验收（心跳前历史反馈）

用户反馈已将原待人工验收内容全部执行一遍，基本功能正常，静默断网现象另列如下。表中“通过”依据用户执行反馈；本轮未由开发代理重做虚拟机实验，也未新增或重新运行自动化测试。

| 项目 | 当前记录 | 实际结果与记录范围 |
| --- | --- | --- |
| 环境分布 | 已确认 | 宿主机：服务端＋alice；虚拟机：bob＋carol。虚拟化软件、网络模式、IP/端口、虚拟机系统/JDK 与代码版本未提供 |
| 入站与互访 | 通过（用户反馈） | 已能跨宿主机与虚拟机通信；具体网络规则配置未提供 |
| 跨系统群聊 | 通过（用户反馈） | 按原人工清单执行后反馈正常；具体消息编号及截图未提供 |
| 跨系统私聊 | 通过（用户反馈） | 私聊操作正常；三方 Socket 隔离另有既有自动化证据，本次未附人工收包记录 |
| 上下线/强退/重连 | 通过（用户反馈） | 用户反馈正常；静默断网后旧连接状态单独记录，不与正常退出混淆 |
| 操作响应 | 通过（用户反馈） | 用户反馈待人工项目已执行且正常；无窗口操作视频 |
| 静默断网观察 | 已完成，记录限制 | bob 发送后检测中断；carol 空闲时未检测，恢复网络后仍需手动断开再连接，或发送后等待中断 |
| 证据来源 | 用户文字反馈 | 未附截图、视频、抓包、精确时间戳；未填写不存在的物理设备或网络配置 |

### Q17 旧版实际过程与解释（心跳加入前）

1. 在虚拟机上断网，宿主机继续运行服务端与 alice。
2. bob 发送一条消息，**不到一分钟**客户端显示连接中断。
3. carol 不进行操作，始终没有检测到断开；恢复虚拟机网络后界面仍停留在断网前的状态，“连接”按钮不可用。
4. carol 可以先点击“断开”，再点击“连接”恢复；也可以发送一条消息，在不到一分钟后出现中断提示，再重新连接。

当时的代码在 LOGIN_OK 后执行 `setSoTimeout(0)`，接收线程持续阻塞读取，没有应用层 PING/PONG 探测。读超时为 0 表示无限等待，因而静默断网不一定触发 EOF 或异常；Java 的 [Socket 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/Socket.html#setSoTimeout(int)) 定义了这一行为。新版本仍可阻塞读，但已有独立心跳 watchdog。

结合代码推断，bob 的发送产生了网络活动，使底层 TCP 错误随后暴露给读或写操作；没有异常日志，无法确认此次中断具体发生在哪个操作。“不到一分钟”仅为本次环境观测，不能写成应用保证的检测时限。TCP 重传失败和保活机制的相关行为见 [RFC 9293](https://www.rfc-editor.org/rfc/rfc9293.html#section-3.8)。

carol 的“已连接”是客户端尚未收到错误时保留的本地状态，并非实时连通性证明。网络恢复不会通知应用重建连接，也不保证旧 TCP 连接仍可使用。界面只有在本地状态为 DISCONNECTED 时才启用“连接”，而“断开”在 CONNECTED 时可用，因此**恢复操作应优先点击“断开 → 连接”**；不需要靠发送测试消息触发检测，也不保证断网期间发送的消息已经送达。

历史结论：Q17 观察符合当时 MVP 无心跳、不保证静默失联及时检测、无自动重连的边界。2026-10-27 已作为 X1 增加独立心跳并通过本机验证；2026-10-28 收到用户虚拟机断网可自动检测断开的正常反馈，详见开头。没有用普通聊天读取超时代替心跳，也不将新反馈倒写为旧版已具备该能力。

## 已处理问题与限制

开发验证已修正协议异常类型与 JDK 同名类的导入冲突、无效字段关联错误 requestId，以及无效超长 requestId 回显可能超帧的问题；最终回归 28 项通过。曾在受限环境运行 Socket 测试导致无法连通，随后在允许本机 TCP 的测试环境重新运行通过，没有修改或跳过失败断言。

仍存在的范围限制：无账号认证/TLS、持久化/离线私聊/自动重连；同步业务写可能受慢客户端阻塞，聊天区历史随进程累积；没有独立 JDK 17 运行时和双物理设备记录。X1 已实现并通过本机验证，另有用户虚拟机断网检测正常反馈；精确期限、完整恢复重连的人工记录仍可补充。不宣称硬实时检测或所有环境已验收。

## 当前会话导出回归（2026-10-30）

本轮 `build.ps1 -o package` 在授权环境通过 **63/0/0/0，57.306 秒**，含原 61 项及新增两项：当前公共群聊/双向私聊过滤、空会话、第三方拒绝、游标范围绑定及客户端 JSON/CSV 文件内容。首次沙箱 TCP/原子移动被禁止，原断言重跑，无删测或放宽。日志 `.tools/conversation-export-build.log`；本阶段无原生 UI/多机/长测人工证据。

## 系统选择器与密码显隐（2026-10-31，阶段 14）

完整 package **74/0/0/0，约 1 分 11 秒**，日志 `.tools/native-picker-password-build.log`；新增 FileDialogsTest 与 AuthenticationPanelTest。computer-use 隔离预览确认现代图片/目录界面（原生导航、地址、搜索、图片筛选）及图片取消恢复；目录路径选择因自动化焦点问题未完成，不宣称实际文件/目录返回验收。未做所有 Windows 版本/DPI/非 Windows 验证，历史 72 项结果不作为本轮重跑。

## 直接另存为与 JSON 排版（2026-11-01，阶段 16）

完整离线 package **81/0/0/0，约 1:12**，日志 .tools/export-save-final.log。HistoryExportTest 覆盖每条一行、合法数组、中文自定义文件名、未授权覆盖拒绝/失败取消不改原文件；FileDialogsTest 新增类型/扩展名一致性。新保存对话框原生交互尚未验收，旧目录选择证据不混用。
