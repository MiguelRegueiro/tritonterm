#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
#ifndef VALUE
#define VALUE 42
#endif
#ifndef BSS_MIB
#define BSS_MIB 1
#endif
static volatile unsigned char space[BSS_MIB * 1024 * 1024];
__attribute__((noinline)) static void code(void) {
    __asm__ volatile(".rept 131072\nnop\n.endr" ::: "memory");
}
int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "worker")) {
        assert(space[0] == 0 && space[sizeof space - 1] == 0);
        space[sizeof space - 1] = 1;
        code();
        printf("VALUE=%d\n", VALUE);
        return 0;
    }
    puts("READY"); fflush(stdout);
    char command[32], path[128], line[256];
    while (fgets(line, sizeof line, stdin)) {
        assert(sscanf(line, "%31s %127s", command, path) == 2);
        int count = !strcmp(command, "PAR") ? 4 : 1;
        pid_t children[4];
        for (int i = 0; i < count; i++) {
            children[i] = fork(); assert(children[i] >= 0);
            if (!children[i]) {
                assert(setenv("TRITONTERM_CNG_PROFILE", "count", 1) == 0);
                if (!strcmp(command, "INHERIT"))
                    assert(unsetenv("TRITONTERM_CNG_IMAGE_CACHE") == 0);
                else
                    assert(setenv("TRITONTERM_CNG_IMAGE_CACHE", !strcmp(command, "OFF") ? "0" : "1", 1) == 0);
                execl(path, path, "worker", NULL); _exit(99);
            }
        }
        for (int i = 0; i < count; i++) {
            int status;
            assert(waitpid(children[i], &status, 0) == children[i] && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        }
        puts("DONE"); fflush(stdout);
    }
    return 0;
}
