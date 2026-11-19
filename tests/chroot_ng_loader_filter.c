#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
int main(int argc, char **argv) {
    assert(argc > 2);
    struct rlimit core = {0, 0}; assert(setrlimit(RLIMIT_CORE, &core) == 0);
    unsigned action = !strcmp(argv[1], "trap") ? SECCOMP_RET_TRAP : SECCOMP_RET_ERRNO | EPERM;
    if (!strcmp(argv[1], "kill")) action = SECCOMP_RET_KILL_PROCESS;
    if (!strcmp(argv[1], "hang")) action = SECCOMP_RET_USER_NOTIF;
    struct sock_filter insns[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_memfd_create, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 3, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, action),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    if (!strcmp(argv[1], "watch")) {
        insns[1].k = SYS_inotify_add_watch;
        insns[3] = (struct sock_filter)BPF_STMT(BPF_JMP | BPF_JA, 0);
    }
    struct sock_fprog filter = {sizeof insns / sizeof insns[0], insns};
    assert(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
    if (!strcmp(argv[1], "hang")) {
        /* Leave a listener open without answering: the child blocks until
         * the runtime's bounded wait kills/reaps it. Test wrapper only. */
        int fd = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER,
                         SECCOMP_FILTER_FLAG_NEW_LISTENER, &filter);
        assert(fd >= 0 && fcntl(fd, F_SETFD, 0) == 0);
    } else assert(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter) == 0);
    execvp(argv[2], argv + 2); perror("execvp"); return 1;
}
