/* 恶意样例：内存耗尽（OOM）。
 * 预期沙箱结果：cgroups v2 memory.max 触发 OOM Kill，映射 MLE。 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(void) {
    size_t step = 16UL * 1024 * 1024; /* 16MB */
    for (int i = 0; i < 1024; i++) {
        char *p = malloc(step);
        if (!p) return 1;
        memset(p, 1, step);
    }
    return 0;
}
