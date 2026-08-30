# 恶意样例：磁盘写满（Python 变体）。
# 预期沙箱结果：写层容量触顶（ENOSPC），RE；宿主机磁盘不受影响。
with open("blob", "wb") as f:
    chunk = b"x" * 4096
    while True:
        f.write(chunk)
