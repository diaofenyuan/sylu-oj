# 恶意样例：fork bomb（Java 变体）。
# 预期沙箱结果：pids.max 抑制（Java 线程即进程），RE/TLE。
public class ForkBomb {
    public static void main(String[] args) {
        while (true) {
            try {
                Thread t = new Thread(() -> {
                    try { Thread.sleep(3600000); } catch (InterruptedException ignored) { }
                });
                t.start();
            } catch (OutOfMemoryError | RuntimeException e) {
                /* pids.max / 线程创建失败：持续重试 */
            }
        }
    }
}
