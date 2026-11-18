#define _GNU_SOURCE
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <stddef.h>
#include <errno.h>
#include <unistd.h>
#include <string.h>
int main(int argc,char **argv) {
 int trap = argc > 1 && !strcmp(argv[1], "--trap");
 if (trap) { argc--; argv++; }
 struct sock_filter f[]={ BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
 BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat2,0,1),
 BPF_STMT(BPF_RET|BPF_K,(trap ? SECCOMP_RET_TRAP : (SECCOMP_RET_ERRNO|ENOSYS))), BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
 struct sock_fprog p={4,f};
 if(argc<2||prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)||prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&p)) return 125;
 execvp(argv[1],argv+1);return 126;
}
