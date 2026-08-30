/* 恶意样例：ptrace/进程注入探测。
 * 预期沙箱结果：ptrace 被 seccomp 白名单拒绝（EPERM/SIGSYS），映射 BSC。 */
#include <stdio.h>
#include <sys/ptrace.h>
#include <sys/types.h>

int main(void) {
    long r = ptrace(PTRACE_ATTACH, 1, NULL, NULL); /* 附着 PID 1 */
    printf("ptrace=%ld\n", r);
    return r == 0 ? 0 : 2;
}
