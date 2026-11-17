# LTalk 单机云部署

同一台服务端保管账号和历史。连接同一台服务端的用户共享公共聊天室。本手册使用 Linux、Java 17+、systemd 247+ 和项目打包的 JAR。首次部署从下节开始，已部署的测试机直接看[更新 JAR 与复测](#更新-jar-与复测)。

## 首次准备

在 Windows 构建项目，将 `target/ltalk-1.0.0.jar` 和 [ltalk.service](../deploy/ltalk.service) 上传到 Ubuntu 的 `/home/ubuntu/`。在 FinalShell 的终端逐行执行，长命令自动折行时按一次回车即可。若有报错，先处理该行再继续。

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless openssl
sudo useradd --system --user-group --home-dir /var/lib/ltalk --shell /usr/sbin/nologin ltalk
sudo install -d -m 0750 -o ltalk -g ltalk /var/lib/ltalk
sudo install -d -m 0755 /opt/ltalk
sudo install -m 0644 /home/ubuntu/ltalk-1.0.0.jar /opt/ltalk/ltalk-1.0.0.jar
```

已有账号和历史时，继续使用原数据目录。迁移到新机器时先按[备份与恢复演练](#备份与恢复演练)验证副本，再交给 `ltalk` 系统用户。

## 先在私网试用

让云主机和测试设备加入同一 VPN 或私有组网。Tailscale 用户可在 Ubuntu 运行 `tailscale ip -4` 查看服务器地址。保持云控制台的 TCP 9000 公网入站关闭，服务端只绑定私网地址。[Tailscale 快速入门](https://tailscale.com/docs/how-to/quickstart)

```bash
read -r -p '输入服务器的私网 IPv4 地址：' LTALK_VPN_IP
test -n "$LTALK_VPN_IP" && sudo -u ltalk java -Dltalk.discovery.enabled=false -Dltalk.dataDir=/var/lib/ltalk -jar /opt/ltalk/ltalk-1.0.0.jar 9000 "$LTALK_VPN_IP" 20
```

Windows 客户端手填私网地址和端口 `9000`，TLS 保持未勾选，由 VPN 加密。验证注册、登录、公共聊天室、私聊和断线后的手动重连。测试结束在启动服务的窗口按 `Ctrl+C`。

## 取得公网 TLS 证书

使用域名时，先将域名的 A 记录指向云主机公网 IPv4，再取得该域名的证书、完整证书链和私钥。若客户端直接填 IP，证书的 SAN 必须包含该 IP。

Oracle Cloud 短期测试使用 Let’s Encrypt 公网 IP 证书，约 6 天有效。以下命令使用支持 `--ip-address` 的 Certbot 5.4 或更新版本。签发时在实例所在子网的安全列表临时添加有状态入站规则，来源 CIDR 填 `0.0.0.0/0`，协议 TCP，目标端口填 `80`，源端口留空。Ubuntu 上还需临时放行同一端口。 [Certbot 的 IP 证书说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot) · [Oracle Cloud 入站规则](https://docs.oracle.com/en/learn/oci-ntw-troubleshoot-1/index.html)

```bash
sudo apt install -y snapd
sudo snap install --classic certbot
read -r -p '输入服务器的公网 IPv4 地址：' PUBLIC_IP
sudo iptables -I INPUT 1 -p tcp --dport 80 -m comment --comment ltalk-cert-test -j ACCEPT
sudo snap run certbot certonly --standalone --preferred-profile shortlived --ip-address "$PUBLIC_IP" --cert-name ltalk-public-ip
```

按提示填写邮箱并同意服务条款。Certbot 退出后，在云控制台删除临时的 TCP 80 入站规则，并在 Ubuntu 撤销对应规则。签发成功时证书位于 `/etc/letsencrypt/live/ltalk-public-ip/`。

```bash
sudo iptables -D INPUT -p tcp --dport 80 -m comment --comment ltalk-cert-test -j ACCEPT
```

## 启动公网 TLS 服务

先停止私网前台进程，释放数据目录。将证书链和私钥打包成 PKCS12。以下 `CERT_DIR` 是上述 IP 证书的目录，使用域名证书时改为相应的目录。证书与密码文件仅供 root 读取，systemd 将其作为凭据交给服务进程。 [systemd 服务凭据文档](https://www.freedesktop.org/software/systemd/man/247/systemd.exec.html)

```bash
CERT_DIR=/etc/letsencrypt/live/ltalk-public-ip
sudo install -d -m 0700 /etc/ltalk
sudo sh -c 'umask 077; test -s /etc/ltalk/server.password || openssl rand -hex 32 > /etc/ltalk/server.password'
sudo openssl pkcs12 -export -in "$CERT_DIR/fullchain.pem" -inkey "$CERT_DIR/privkey.pem" -name ltalk -out /etc/ltalk/server.p12.new -passout file:/etc/ltalk/server.password
sudo chmod 0600 /etc/ltalk/server.p12.new /etc/ltalk/server.password
sudo mv -fT /etc/ltalk/server.p12.new /etc/ltalk/server.p12
```

首次安装服务时执行以下命令。日志显示「LTalk TLS 已启动」后再开放聊天端口。

```bash
sudo install -m 0644 /home/ubuntu/ltalk.service /etc/systemd/system/ltalk.service
sudo systemctl daemon-reload
sudo systemctl start ltalk
sudo journalctl -u ltalk -n 20 --no-pager
```

需要开机自启时，另行执行 `sudo systemctl enable ltalk`，并验证重启后证书、服务及防火墙规则均可正常使用。

云控制台添加有状态入站规则 `ltalk-public-test`，目标端口 TCP `9000`，来源 CIDR 填测试电脑的公网 IPv4 加 `/32`。从这台电脑直连实例公网 IP 的 SSH 会话里，`echo "$SSH_CONNECTION"` 的第一列为服务端看到的来源 IP。Ubuntu 按下节的方式检查并放行 TCP 9000。公网客户端断开 Tailscale，填写证书对应的域名或公网 IP，端口 `9000`，勾选「使用 TLS 加密连接」。使用私有 CA 时，需要给客户端 Java 配置信任库。 [Oracle JSSE 文档](https://docs.oracle.com/en/java/javase/17/security/java-secure-socket-extension-jsse-reference-guide.html)

验收时登录两个账号，检查公共群聊、已获许可的私聊、历史恢复及断线重连。证书与输入地址不匹配时应拒绝连接。用不同接入网络复测时，确保来源地址已获得访问权限。[已完成的云端验收](test-report.md#oracle-cloud-私网与公网验收)

## 更新 JAR 与复测

同一版本复测时执行 `sudo systemctl start ltalk`，然后从第 2 步核对网络和证书。换版本时从第 1 步开始。

1. Windows 构建新版并上传 `target/ltalk-1.0.0.jar` 到 `/home/ubuntu/`。以下命令保留上一个版本。涉及数据格式变更时，停止服务后按[备份与恢复演练](#备份与恢复演练)备份 `/var/lib/ltalk`，随后按新版本的迁移说明操作。

   ```bash
   sudo systemctl stop ltalk
   sudo cp -p /opt/ltalk/ltalk-1.0.0.jar /opt/ltalk/ltalk-1.0.0.jar.bak
   sudo install -m 0644 /home/ubuntu/ltalk-1.0.0.jar /opt/ltalk/ltalk-1.0.0.jar
   sudo systemctl start ltalk
   sudo journalctl -u ltalk -n 20 --no-pager
   ```

   如果需要回退，停服务后用 `sudo install -m 0644 /opt/ltalk/ltalk-1.0.0.jar.bak /opt/ltalk/ltalk-1.0.0.jar` 装回旧 JAR。涉及数据迁移时，同时按该版本的迁移说明恢复兼容的数据。服务文件有更新时，安装后先运行 `sudo systemctl daemon-reload`。

2. 核对云端 TCP 9000 入站规则的来源地址。Ubuntu 的测试规则是临时的，实例重启后可用下面一行先检查再添加。

   ```bash
   sudo iptables -C INPUT -p tcp --dport 9000 -m comment --comment ltalk-public-test -j ACCEPT || sudo iptables -I INPUT 1 -p tcp --dport 9000 -m comment --comment ltalk-public-test -j ACCEPT
   ```

3. 核对 LTalk 配置所用的证书文件的有效期。证书到期可按[取得公网 TLS 证书](#取得公网-tls-证书)重新走验证流程，打包后运行 `sudo systemctl restart ltalk`。长期开放时需验证自动续签、重新打包和重启服务的流程。

   ```bash
   sudo openssl pkcs12 -in /etc/ltalk/server.p12 -passin file:/etc/ltalk/server.password -clcerts -nokeys | openssl x509 -noout -enddate
   ```

4. Windows 宿主机和虚拟机都退出旧客户端，将各自的 JAR 换成同一份新版，再双击 `start-client.cmd`。连接失败时，先在 Windows PowerShell 执行 `Test-NetConnection -ComputerName <公网地址> -Port 9000`（将 `<公网地址>` 换成实际地址）。如果 TCP 已通，继续检查证书有效期、地址匹配和客户端 TLS 选项。

5. 用演示账号复测登录后首批公共消息出现的时间、离线好友留言、独立群加入前与退出后的权限，以及在两个会话间切换时的未读和引用跳转。记录服务端与客户端所用提交、网络环境、复现步骤和耗时。旧版公网验收范围见 [测试报告](test-report.md#oracle-cloud-私网与公网验收)。

6. 测试结束可运行 `sudo systemctl stop ltalk`，用 `sudo systemctl is-active ltalk` 确认显示 `inactive`。若要关闭测试端口，在云控制台移除 `ltalk-public-test`，并执行 `sudo iptables -D INPUT -p tcp --dport 9000 -m comment --comment ltalk-public-test -j ACCEPT`。账号和聊天记录保存在 `/var/lib/ltalk`。

## 容量与限制

TLS 默认共 140 条连接，最多 24 条尚未认证。TCP 接入后的 TLS 与初始应用握手共享 10 秒期限，完成 HELLO 后有 60 秒完成认证。以下整数属性覆盖默认值，以 `-D属性=数值` 放在 `-jar` 之前。频率按各来源自身的一分钟窗口计算。

| JVM 属性 | 默认 | 作用 |
| --- | --- | --- |
| `ltalk.public.unauthenticated` | 24 | 未认证连接上限 |
| `ltalk.public.authSeconds` | 60 | HELLO 后认证期限 |
| `ltalk.public.connectionsPerIp` | 60 | 单来源 IP 的接入次数 |
| `ltalk.public.loginsPerIp` | 24 | 单来源 IP 的登录请求次数 |
| `ltalk.public.registrationsPerIp` | 4 | 单来源 IP 的注册请求次数 |
| `ltalk.public.loginsPerAccount` | 8 | 同账号的登录与改密请求合计 |
| `ltalk.public.hashingWorkers` | 至多 4 | 同时计算密码散列的连接数 |

同一学校、公司或中转节点可能让多名用户共享一个公网 IP。结合实际注册和重连波次调整单 IP 阈值。超过限制的请求会明确失败，可稍后再试。每条连接的发送队列最多 128 帧、512 KiB 等待数据，另有一帧可正在写入。服务端每分钟记录连接数、认证限流和慢连接淘汰等计数。部署后可用 `journalctl -u ltalk` 和 `systemctl status ltalk` 观察进程和资源。

## 备份与恢复演练

停止服务后完整备份数据目录，确保账号、`social.json`（好友关系、私聊许可与群成员权限）、`history/`（含群组日志、删除标记和时间元数据）和 `attachments/`（图片与文件原始字节）属于同一快照。证书、私钥及密码单独安全备份。

图片和附件使用与聊天相同的 TCP 端口，单独占用一个连接名额，公网模式仍使用 TLS。附件默认总配额为 1 GiB，可在服务端 JVM 配置 `-Dltalk.attachments.quotaBytes=字节数`。文件随归档保留，容量规划还需计入账号、历史及备份使用的空间。

```sh
sudo install -d -m 0700 /var/backups/ltalk
sudo systemctl stop ltalk
sudo tar --exclude='./server.lock' -C /var/lib/ltalk -czf "/var/backups/ltalk/ltalk-$(date +%F).tar.gz" .
```

只做备份时，可在完成后用 `sudo systemctl start ltalk` 恢复服务。升级时先完成版本更换和需要的数据迁移，再启动服务。

恢复演练时先解压到独立目录，赋予 `ltalk` 用户权限，并指向该目录在回环地址与临时端口启动。用测试账号验证登录、公共与私聊记录，以及本人已删除的记录仍不显示。迁移生产数据时先保留原目录，再用恢复验证后的副本启动。

## 本机压测

```powershell
powershell -NoProfile -File .\build.ps1 -o '-Dtest=PublicCapacityTest' '-Dltalk.capacity.seconds=180' test
```

测试用临时证书和隔离目录，使用同一回环 IP，因此为这次测试单独提高了该 IP 的频率阈值。以 `PUBLIC_CAPACITY` 日志查看结果。云主机验收要从真实线路复测，同时观察实际带宽、登录波次和用户行为。
