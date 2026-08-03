#include <stdio.h>
#include <stdlib.h>
#include <time.h>

int run(int n) {
    int sum = 0;
    for (int i = 0; i < n; i++) {
        if ((i & 1) == 0) {
            sum += i;
        } else {
            sum -= i;
        }
    }
    return sum;
}

int main(int argc, char *argv[]) {
    int n = argc > 1 ? atoi(argv[1]) : 1000000;
    run(10000); /* warmup */

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);
    int result = run(n);
    clock_gettime(CLOCK_MONOTONIC, &end);

    double elapsed_ms = (end.tv_sec - start.tv_sec) * 1000.0
                      + (end.tv_nsec - start.tv_nsec) / 1e6;
    printf("n=%d result=%d elapsed_ms=%.4f\n", n, result, elapsed_ms);
    return 0;
}
