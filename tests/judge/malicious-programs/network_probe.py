# 恶意样例：网络探测（Python 变体：内网、元数据地址与 DNS）。
# 预期沙箱结果：无网卡/路由/DNS，连接全部失败，映射 BSC/RE；
# 元数据地址 169.254.169.254 不可达。
import socket

targets = [
    ("169.254.169.254", 80),
    ("10.0.0.1", 3306),
    ("192.168.1.1", 53),
]
for host, port in targets:
    try:
        s = socket.create_connection((host, port), timeout=2)
        s.close()
    except OSError:
        pass
print("done")
