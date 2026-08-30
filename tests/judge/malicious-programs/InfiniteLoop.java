# 恶意样例：超时循环（Java 变体）。
# 预期沙箱结果：墙钟超时，TLE。
public class InfiniteLoop {
    public static void main(String[] args) {
        while (true) {
            Math.sqrt(System.nanoTime());
        }
    }
}
