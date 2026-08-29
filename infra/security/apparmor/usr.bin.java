# usr.bin.java —— API 区 Spring Boot / JDK（Task 3）
# 应用目录 /opt/oj 运行时只读，读写路径白名单限定为 /var/lib/oj 与 /var/log/oj。
#include <tunables/global>

/usr/bin/java {
  #include <abstractions/base>
  #include <abstractions/nameservice>
  #include <abstractions/openssl>

  # 绑定 API 8443 / Judge Gateway（内网 mTLS）
  capability net_bind_service,

  # 运行时与制品只读
  /usr/lib/jvm/** r,
  /opt/oj/app/** r,
  /etc/oj/application-prod.yml r,

  # 业务运行数据与日志（systemd ReadWritePaths 与之一致）
  /var/lib/oj/** rw,
  /var/log/oj/** rw,

  # 出网目标受虚拟防火墙控制（MySQL 3306 / Redis 6379 / RabbitMQ 5671，均 TLS）
  network inet stream,
  network inet6 stream,

  # 禁止 API 访问密钥目录、其他服务数据、宿主机目录与 Docker Socket
  deny /etc/oj/secrets/** rwklx,
  deny /etc/oj/credentials/** rwklx,
  deny /var/lib/mysql/** rwklx,
  deny /var/lib/redis/** rwklx,
  deny /var/lib/rabbitmq/** rwklx,
  deny /var/run/docker.sock rw,
  deny /run/docker.sock rw,

  /tmp/** rw,
}
