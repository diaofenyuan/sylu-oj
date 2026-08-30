/* 恶意样例：路径穿越与敏感文件读取。
 * 预期沙箱结果：只读根文件系统 + 最小文件树，/etc/shadow、宿主机根目录、
 * 临时写层之外的一切均不可读；程序退出码非 0，映射 RE/WA，无数据外泄。 */
#include <stdio.h>
#include <stdlib.h>

static long try_read(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    long n = 0;
    int c;
    while ((c = fgetc(f)) != EOF) n++;
    fclose(f);
    return n;
}

int main(void) {
    long leaked = 0;
    const char *paths[] = {
        "/etc/passwd", "/etc/shadow", "/proc/1/environ",
        "/workspace/../../etc/passwd", "/host", "/mnt/data",
    };
    for (int i = 0; i < 6; i++) {
        leaked += try_read(paths[i]);
    }
    /* 任何成功读取都意味着隔离失败；正常情况下 leaked == 0 */
    printf("leaked=%ld\n", leaked);
    return leaked == 0 ? 1 : 0;
}
