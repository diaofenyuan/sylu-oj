/* 恶意样例：无限循环（墙钟/CPU 时间超限）。
 * 预期沙箱结果：SIGKILL 超时终止，映射 TLE；不占用宿主机资源。 */
int main(void) {
    while (1) {
    }
    return 0;
}
