# LTalk 局域网聊天室

LTalk 是一个 Java 多人聊天课程项目，由一个 TCP 服务端和多个 Swing 桌面客户端组成，支持局域网聊天和可选的 TLS 公网部署。

**目录** · [功能一览](#功能一览) · [快速开始](#快速开始) · [技术栈与架构](#技术栈与架构) · [测试](#测试) · [文档索引](#文档索引)

## 功能一览

需求编号和各项验收入口见[需求与验收](docs/requirements.md)。

| 功能 | 状态 | 对应需求 | 详见文档 |
| --- | --- | --- | --- |
| 连接、注册登录、心跳和手动重连 | 已实现 | R-01、R-02、R-08、X1 | [连接与账号](docs/user-guide.md#连接与账号)、[断线重连](docs/user-guide.md#断线重连) |
| 公共聊天室、在线列表、好友离线留言与陌生人私聊许可 | 已实现 | R-03～R-05、R-10、X5 | [公共聊天室](docs/user-guide.md#公共聊天室)、[私聊与好友](docs/user-guide.md#私聊与好友) |
| 独立群组、入群审核、邀请和管理员转让 | 已实现 | R-04、好友与独立群组 | [群组](docs/user-guide.md#群组) |
| 历史分页、搜索和未读定位 | 已实现 | X4、未读定位与引用 | [历史、搜索与未读](docs/user-guide.md#历史搜索与未读) |
| 引用、复制、60 秒内撤回、本人删除及 JSON/CSV 导出 | 已实现 | X2、X4、消息撤回、未读定位与引用 | [消息操作](docs/user-guide.md#消息操作) |
| 彩色 Emoji、图片与文件传输 | 已实现 | R-06、X6 | [Emoji 与附件](docs/user-guide.md#emoji-与附件) |
| 昵称、头像、改密、主题和窄屏布局 | 已实现 | R-06、R-09、X3、外观可读性与直观选色 | [资料与外观](docs/user-guide.md#资料与外观) |
| SQLite 持久化与历史访问控制 | 已实现 | SQLite 存储、X4 | [账号认证、数据库与导出](docs/design.md#账号认证数据库与导出) |
| IPv4 局域网发现与单机公网 TLS | 已实现，TLS 按需启用 | R-01、R-11、R-12 | [局域网运行与云部署](docs/deployment.md) |

## 快速开始

以下命令在项目根目录（包含 `pom.xml`）运行。运行已打包 JAR 需要 Java 17 或更高版本，客户端需要图形桌面。已有 `target/ltalk-1.0.0.jar` 可从第 2 步开始。

### 1. 构建

源码构建需要 JDK 17+。Windows PowerShell 可用项目脚本准备 Maven 并构建，首次准备依赖需要联网。

```powershell
powershell -NoProfile -File .\build.ps1 package
```

也可使用已安装的 Maven 3.9.x，在有图形桌面的环境中执行 `mvn package`。构建会运行测试并生成含依赖的 `target/ltalk-1.0.0.jar`。

### 2. 启动服务端

以下示例使用回环地址和独立的演示数据库。同一目录再次启动时会保留演示账号和历史。

```sh
java "-Dltalk.dataDir=target/demo-data" -Dltalk.discovery.enabled=false -jar target/ltalk-1.0.0.jar 9000 127.0.0.1
```

### 3. 启动客户端

保持服务端运行，另开终端执行以下命令。启动页会尝试连接本机服务端。首次使用点击「立即注册」，填好昵称、用户名、密码和确认密码，注册成功后自动登录。

```sh
java -cp target/ltalk-1.0.0.jar cn.ltalk.client.ChatWindow 127.0.0.1 9000
```

Windows 也可双击根目录的 `start-client.cmd`，在启动页填 `127.0.0.1` 和 `9000`，保持 TLS 未勾选，再点「连接服务器」。跨设备连接和公网配置见[部署手册](docs/deployment.md)。

### 4. 验证收发

启动两个客户端，分别注册或登录 `alice` 和 `bob`。用 `alice` 在公共聊天室发一条消息，确认 `bob` 的窗口能收到。

接下来可以试用好友私聊、建群后邀请好友入群发言、发送图片和文件（接收方可预览图片、保存附件），以及引用回复、60 秒内撤回消息和导出当前会话 JSON。也可以在服务端窗口按 **Ctrl+C** 停服，看到断线提示后重新启动服务端，再在客户端手动重连。[详细操作](docs/user-guide.md)

## 技术栈与架构

- Maven 单模块以 Java 17 为编译目标，同一份含依赖 JAR 提供服务端和客户端入口。
- 服务端通过阻塞 TCP 承载聊天，使用 Gson 处理 UTF-8、LF 分隔的 JSON 帧。
- 登录身份绑定连接。服务端按会话权限路由，每条连接使用有界发送队列隔离慢客户端。
- Swing 和 Java2D 负责界面。连接、收发与文件处理在后台，会话和组件更新在 EDT。
- SQLite 保存账号、社交关系、历史及附件元数据，附件原文件随服务端数据目录保存。
- 局域网自动发现使用 UDP。公网可启用 TLS，客户端在认证前校验证书与主机名。

模块关系、线程生命周期和存储边界见[架构与线程设计](docs/design.md)。

## 测试

当前功能基线的 Windows 本机完整离线回归 **152 项通过，0 失败、错误或跳过**。环境、复验命令和多机验证范围见[测试报告](docs/test-report.md)。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [用户指南](docs/user-guide.md) | 从注册、聊天和群管理，到消息操作、附件与重连。 |
| [运行与部署](docs/deployment.md) | 本地与局域网参数、云服务器 TLS 部署、换版和备份恢复。 |
| [需求与验收](docs/requirements.md) | 功能编号、业务边界与对应验证入口。 |
| [架构与线程](docs/design.md) | 服务端路由、Swing 线程、SQLite 事务和资源管理。 |
| [通信协议](docs/protocol.md) | TCP/UDP 帧、认证、消息类型与错误码。 |
| [桌面 UI 设计](docs/ui-design.md) | 界面组件、交互规则和扩展位置。 |
| [测试报告](docs/test-report.md) | 自动化、真实窗口和跨设备证据及待验收项。 |
| [开发交接](docs/STATUS.md) | 当前功能基线、代码入口和接续步骤。 |
| [仓库协作约定](AGENTS.md) | 接续开发、验证和 Git 提交规则。 |
| [开发日志](docs/DEVLOG.md) | 各阶段的决策、修复和验证记录。 |
| [开发路线图](docs/ROADMAP.md) | 已落地的扩展和后续工作。 |
| [原始课程任务拆解](docs/局域网聊天项目任务拆解.md) | MVP、阶段依赖与最初的交付计划。 |
| [规格驱动开发技能](.agents/skills/java-chatroom-engineering/SKILL.md) | 可复用的规格与评测模板，以及 LTalk 验证案例。 |
