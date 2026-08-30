# 恶意样例：fork bomb（Python 变体）。
# 预期沙箱结果：pids.max 抑制，RE/TLE；不逃逸宿主机。
import os
import time

while True:
    try:
        pid = os.fork()
    except OSError:
        continue
    if pid == 0:
        time.sleep(3600)
