# LTalk 单机云部署

同一台服务端保管账号和历史。局域网与远程用户连入同一地址后会共享公共聊天室。本手册使用 Linux、Java 17+、systemd 247+ 和项目打包的 JAR。云主机、域名和证书由部署方提供。

## 先在私网试用

让云主机和测试设备加入同一 VPN 或私有组网。第一轮可用 VPN 提供加密，在云安全组和主机防火墙仅允许 VPN 接口或网段访问 TCP 9000。将示例中的私网地址、JAR 和数据路径改成实际值。从安装目录执行以下命令。

```sh
java -Dltalk.discovery.enabled=false -Dltalk.dataDir=/var/lib/ltalk \
  -jar /opt/ltalk/ltalk-1.0.0.jar 9000 10.8.0.2 140
```

在客户端手填该 VPN 地址和端口。验证两台设备的注册、登录、公共聊天室、私聊、心跳和断线后的手动重连。账号和历史保存在上面的固定目录，以后切换到 TLS 服务时继续使用该目录。尽量从第一次试用起就以同一个非特权系统用户运行服务，便于保留数据权限。

## 使用 TLS 提供公网连接

1. 将域名的 IPv4 DNS 记录指向云主机公网地址。取得该域名的服务器证书、完整证书链和私钥。客户端使用该域名连接。如果用 IP，证书的 SAN 需要包含该 IP。
2. 在仓库外的私有目录将密钥和证书链导出为 PKCS12。以下命令交互提示输入导出密码。输入的同一密码放入 `/etc/ltalk/server.password` 的第一行。服务定义通过 systemd 凭据目录向进程提供这两个文件。[systemd 服务凭据文档](https://www.freedesktop.org/software/systemd/man/247/systemd.exec.html)

   ```sh
   umask 077
   openssl pkcs12 -export -in /path/to/fullchain.pem -inkey /path/to/privkey.pem \
     -name ltalk -out /path/to/private/server.p12
   ```

3. 安装程序，创建 `ltalk` 系统用户和组。密钥存储与密码保持仅 root 可读，JAR 与服务文件由 root 持有。使用现有数据时，将完整的原数据目录内容交给服务账号访问。

   ```sh
   sudo useradd --system --user-group --home-dir /var/lib/ltalk --shell /usr/sbin/nologin ltalk
   sudo install -d -m 0755 /opt/ltalk
   sudo install -d -m 0700 /etc/ltalk
   sudo install -m 0644 target/ltalk-1.0.0.jar /opt/ltalk/ltalk-1.0.0.jar
   sudo install -m 0600 /path/to/private/server.p12 /etc/ltalk/server.p12
   sudoedit /etc/ltalk/server.password
   sudo chmod 0600 /etc/ltalk/server.password
   sudo install -m 0644 deploy/ltalk.service /etc/systemd/system/ltalk.service
   sudo systemctl daemon-reload
   sudo systemctl enable --now ltalk
   sudo journalctl -u ltalk -f
   ```

4. 云安全组和主机防火墙允许预期用户访问 TCP 9000。TLS 服务会关闭 UDP 发现。Windows 客户端启动后勾选「使用 TLS 加密连接」，填写证书对应的主机名和端口，连接成功后再输入账号密码。
5. 从外部网络实际连接两台客户端。正向验证登录、群聊、私聊、断线重连和历史恢复。用与证书不匹配的主机名验证失败提示。使用私有 CA 时将其加入所用 Java 运行时的信任库，也可通过 `javax.net.ssl.trustStore` 指定独立的信任库。继续使用主机名校验。[Oracle JSSE 文档](https://docs.oracle.com/en/java/javase/17/security/java-secure-socket-extension-jsse-reference-guide.html)

证书续期后重新生成 PKCS12，更新受保护的文件，再执行 `sudo systemctl restart ltalk` 加载新证书。服务中断期间客户端可按原入口重连。

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

停止服务后完整备份数据目录，确保账号、聊天归档、公共记录删除标记和时间元数据属于同一快照。证书、私钥及密码单独安全备份。

```sh
sudo install -d -m 0700 /var/backups/ltalk
sudo systemctl stop ltalk
sudo tar --exclude='./server.lock' -C /var/lib/ltalk -czf "/var/backups/ltalk/ltalk-$(date +%F).tar.gz" .
sudo systemctl start ltalk
```

恢复演练时先解压到独立目录，赋予 `ltalk` 用户权限，并指向该目录在回环地址与临时端口启动。用测试账号验证登录、公共与私聊记录，以及本人已删除的记录仍不显示。迁移生产数据时先保留原目录，再用恢复验证后的副本启动。

## 本机压测

```powershell
powershell -NoProfile -File .\build.ps1 -o '-Dtest=PublicCapacityTest' '-Dltalk.capacity.seconds=180' test
```

测试用临时证书和隔离目录，使用同一回环 IP，因此为这次测试单独提高了该 IP 的频率阈值。以 `PUBLIC_CAPACITY` 日志查看结果。云主机验收要从真实线路复测，同时观察实际带宽、登录波次和用户行为。
