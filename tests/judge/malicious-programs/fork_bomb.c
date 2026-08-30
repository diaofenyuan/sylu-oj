#include <unistd.h>
#include <stdio.h>

/* 恶意样例：fork bomb（设计 6.2 / 11 节第 5 条）。
 * 预期沙箱结果：cgroups v2 pids.max 抑制，进程树被 SIGKILL/资源耗尽终止，
 * 映射 RE/TLE；宿主机与其他沙箱不受影响。 */
int main(void) {
    while (1) {
        pid_t pid = fork();
        if (pid < 0) {
            /* pids.max 触顶：fork 失败即持续重试 */
            continue;
        }
        if (pid == 0) {
            pause();
        }
    }
    return 0;
}
