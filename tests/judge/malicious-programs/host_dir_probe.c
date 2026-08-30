/* 恶意样例：宿主机目录与控制面探测（/proc、cgroup、宿主根目录）。
 * 预期沙箱结果：宿主机 /proc、宿主根目录、cgroup 控制文件全部不可见或不可写。 */
#include <stdio.h>
#include <stdlib.h>

static int writable(const char *path) {
    FILE *f = fopen(path, "a");
    if (!f) return 0;
    fclose(f);
    return 1;
}

int main(void) {
    const char *paths[] = {
        "/proc/1/environ", "/proc/self/mountinfo",
        "/sys/fs/cgroup/cgroup.procs", "/host/proc/1/environ",
        "/etc/oj-judge/seccomp.json",
    };
    int hit = 0;
    for (int i = 0; i < 5; i++) {
        if (writable(paths[i])) hit++;
    }
    printf("writable=%d\n", hit);
    return hit == 0 ? 1 : 0;
}
