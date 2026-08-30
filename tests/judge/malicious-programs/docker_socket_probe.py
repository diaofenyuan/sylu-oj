# 恶意样例：容器运行时控制面探测（Docker/CRIO Socket 与特权路径）。
# 预期沙箱结果：socket 文件不存在或不可连接；seccomp 拒绝相关系统调用。
import socket

paths = [
    "/var/run/docker.sock",
    "/var/run/crio/crio.sock",
    "/run/containerd/containerd.sock",
]
hit = 0
for p in paths:
    try:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(p)
        s.close()
        hit += 1
    except OSError:
        pass
print("connected=%d" % hit)
raise SystemExit(0 if hit == 0 else 1)
