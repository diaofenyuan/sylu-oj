#include <stdio.h>

/* 良性功能样例：A+B（stdin 输入，四语言功能正确性验收）。 */
int main(void) {
    long a, b;
    if (scanf("%ld %ld", &a, &b) != 2) return 1;
    printf("%ld\n", a + b);
    return 0;
}
