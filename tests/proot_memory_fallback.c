#define _GNU_SOURCE
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s PROGRAM [ARGUMENTS...]\n", argv[0]);
        return 2;
    }
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_process_vm_readv, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_process_vm_writev, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {
        .len = sizeof(instructions) / sizeof(instructions[0]),
        .filter = instructions,
    };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program)) {
        perror("install process_vm fallback filter");
        return 1;
    }
    char byte = 0;
    struct iovec memory = { .iov_base = &byte, .iov_len = sizeof(byte) };
    errno = 0;
    if (process_vm_readv(getpid(), &memory, 1, &memory, 1, 0) != -1 || errno != EPERM) {
        fprintf(stderr, "process_vm_readv was not denied\n");
        return 1;
    }
    errno = 0;
    if (process_vm_writev(getpid(), &memory, 1, &memory, 1, 0) != -1 || errno != EPERM) {
        fprintf(stderr, "process_vm_writev was not denied\n");
        return 1;
    }
    execvp(argv[1], argv + 1);
    perror(argv[1]);
    return 1;
}
