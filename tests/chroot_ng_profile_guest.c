#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static void *worker(void *unused) {
    (void)unused;
    for (int i = 0; i < 30; i++) {
        struct stat st;
        assert(stat("/fixture", &st) == 0 && st.st_size == 7);
        /* Keep explicit full-resolver coverage alongside canonical stat's
         * optimized path: the profiling checks require resolver activity. */
        assert(stat("/./fixture", &st) == 0 && st.st_size == 7);
    }
    return NULL;
}
static void *exec_worker(void *unused) {
    (void)unused;
    execl("/bin/profile-guest", "profile-guest", NULL);
    _exit(96);
}
int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "pipe")) {
        puts("READY");
        fflush(stdout);
        assert(getchar() == '\n');
        return 7;
    }
    if (argc > 1 && !strcmp(argv[1], "exec-nonleader")) {
        pthread_t t;
        assert(pthread_create(&t, NULL, exec_worker, NULL) == 0);
        for (;;) pause();
    }
    if (argc > 1 && !strcmp(argv[1], "exec-off")) {
        assert(setenv("TRITONTERM_CNG_PROFILE", "off", 1) == 0);
        execl("/bin/profile-guest", "profile-guest", NULL);
        return 99;
    }
    if (argc > 1 && !strcmp(argv[1], "exec-fail")) {
        execl("/missing", "missing", NULL);
        assert(errno == ENOENT);
        puts("PROFILE_EXEC_FAILED_OK");
        return 0;
    }
    if (argc > 1 && !strcmp(argv[1], "activate")) {
        assert(setenv("TRITONTERM_CNG_PROFILE", "timing", 1) == 0);
        execl("/bin/profile-guest", "profile-guest", NULL);
        return 98;
    }
    if (argc > 1 && !strcmp(argv[1], "exec-thread")) {
        /* pthread is exercised below; this path execs after a joined worker. */
        pthread_t t;
        assert(pthread_create(&t, NULL, worker, NULL) == 0);
        assert(pthread_join(t, NULL) == 0);
        execl("/bin/profile-guest", "profile-guest", NULL);
        return 97;
    }
    pthread_t threads[4];
    for (unsigned i = 0; i < 4; i++) assert(pthread_create(&threads[i], NULL, worker, NULL) == 0);
    for (unsigned i = 0; i < 4; i++) assert(pthread_join(threads[i], NULL) == 0);
    pid_t child = fork();
    assert(child >= 0);
    if (!child) { worker(NULL); _exit(0); }
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    puts("PROFILE_GUEST_OK");
    return 7;
}
