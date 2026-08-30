/* 恶意样例：网络探测（socket 系统调用）。
 * 预期沙箱结果：seccomp 白名单拒绝 socket()（EPERM/SIGSYS），
 * 映射 BSC 并记录安全审计事件；无任何网络路径可达。 */
#include <stdio.h>
#include <sys/socket.h>
#include <errno.h>

int main(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        /* 预期路径：被 seccomp 拒绝 */
        return 2;
    }
    (void)connect_stub(fd);
    return 1;
}

int connect_stub(int fd) {
    return fd;
}
