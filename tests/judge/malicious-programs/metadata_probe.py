# 恶意样例：云元数据与宿主机凭据探测。
# 预期沙箱结果：无网络路径，元数据/宿主机 proc 不可达，RE/WA。
import urllib.request

urls = [
    "http://169.254.169.254/latest/meta-data/",
    "http://100.100.100.200/latest/meta-data/",
    "file:///proc/1/environ",
]
leaked = 0
for u in urls:
    try:
        with urllib.request.urlopen(u, timeout=2) as r:
            leaked += len(r.read())
    except Exception:
        pass
print("leaked=%d" % leaked)
raise SystemExit(0 if leaked == 0 else 1)
