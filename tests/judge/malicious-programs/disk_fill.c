/* 恶意样例：磁盘写满。
 * 预期沙箱结果：临时写层 tmpfs/ulimit fsize 触顶（SIGXFSZ 或 ENOSPC），
 * 映射 RE；宿主机磁盘不受影响。 */
#include <stdio.h>
#include <stdlib.h>

int main(void) {
    FILE *f = fopen("blob", "w");
    if (!f) return 1;
    char chunk[4096];
    for (int i = 0; i < 4096; i++) chunk[i] = (char)(i & 0xff);
    while (1) {
        fwrite(chunk, 1, sizeof(chunk), f);
    }
    return 0;
}
