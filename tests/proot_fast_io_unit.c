#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <unistd.h>

static int capture_prctl(int option, ...);
#define prctl capture_prctl
#include "syscall/seccomp.c"
#undef prctl

static int metadata_mkdir_calls;
static int counted_mkdir(const char *path, mode_t mode)
{
    metadata_mkdir_calls++;
    return mkdir(path, mode);
}
#define mkdir counted_mkdir
#include "extension/fake_id0/helper_functions.c"
#undef mkdir

static struct sock_fprog captured;

static int capture_prctl(int option, ...)
{
    va_list arguments;
    va_start(arguments, option);
    if (option == PR_SET_SECCOMP) {
        assert(va_arg(arguments, int) == SECCOMP_MODE_FILTER);
        const struct sock_fprog *program = va_arg(arguments, const struct sock_fprog *);
        captured.len = program->len;
        captured.filter = malloc(program->len * sizeof(*program->filter));
        assert(captured.filter != NULL);
        memcpy(captured.filter, program->filter, program->len * sizeof(*program->filter));
    }
    va_end(arguments);
    return 0;
}

static uint32_t evaluate(uint32_t arch, uint32_t syscall)
{
    uint32_t accumulator = 0;
    for (size_t pc = 0; pc < captured.len; pc++) {
        struct sock_filter instruction = captured.filter[pc];
        switch (instruction.code) {
        case BPF_LD | BPF_W | BPF_ABS:
            assert(instruction.k == offsetof(struct seccomp_data, arch) ||
                   instruction.k == offsetof(struct seccomp_data, nr));
            accumulator = instruction.k == offsetof(struct seccomp_data, arch) ? arch : syscall;
            break;
        case BPF_JMP | BPF_JEQ | BPF_K:
            pc += accumulator == instruction.k ? instruction.jt : instruction.jf;
            break;
        case BPF_JMP | BPF_JA | BPF_K:
            pc += instruction.k;
            break;
        case BPF_RET | BPF_K:
            return instruction.k;
        default:
            abort();
        }
    }
    abort();
}

static void test_filter(void)
{
    const Sysnum allowed[] = {
        PR_read, PR_write, PR_readv, PR_writev, PR_pread64, PR_pwrite64,
        PR_close, PR_lseek, PR_mmap, PR_mmap2, PR_munmap, PR_mprotect,
        PR_mremap, PR_madvise, PR_sched_yield, PR_nanosleep,
        PR_clock_nanosleep, PR_clock_gettime, PR_gettimeofday, PR_getrandom,
#if defined(ARCH_ARM64)
        PR_futex,
#endif
    };
    const SeccompArch architectures[] = SECCOMP_ARCHS;
    assert(enable_syscall_passthrough() == 0);
    assert(captured.len > 0);
    for (size_t a = 0; a < sizeof(architectures) / sizeof(architectures[0]); a++) {
        for (uint32_t syscall = 0; syscall < 1024; syscall++) {
            bool allow = false;
            for (size_t abi = 0; abi < architectures[a].nb_abis; abi++)
                for (size_t i = 0; i < sizeof(allowed) / sizeof(allowed[0]); i++)
                    allow |= detranslate_sysnum(architectures[a].abis[abi], allowed[i]) == syscall;
            assert(evaluate(architectures[a].value, syscall) ==
                   (allow ? SECCOMP_RET_ALLOW : SECCOMP_RET_TRACE + FILTER_SYSEXIT));
        }
    }
    assert(evaluate(0, 0) == SECCOMP_RET_KILL);
    free(captured.filter);
    const FilteredSysnum translated[] = { { PR_openat, FILTER_SYSEXIT }, FILTERED_SYSNUM_END };
    assert(set_seccomp_filters(translated, false) == 0);
    assert(evaluate(architectures[0].value, detranslate_sysnum(ABI_DEFAULT, PR_openat)) ==
           SECCOMP_RET_TRACE + FILTER_SYSEXIT);
    assert(evaluate(architectures[0].value, detranslate_sysnum(ABI_DEFAULT, PR_read)) == SECCOMP_RET_ALLOW);
    free(captured.filter);
    Tracee tracee = { .ctx = talloc_new(NULL) };
    assert(tracee.ctx != NULL);
    assert(enable_syscall_filtering(&tracee) == 0);
    assert(evaluate(architectures[0].value, detranslate_sysnum(ABI_DEFAULT, PR_openat)) == SECCOMP_RET_TRACE);
    free(captured.filter);
    talloc_free(tracee.ctx);
}

static void test_metadata(void)
{
    char directory[] = "/tmp/tritonterm-meta-unit.XXXXXX";
    char path[PATH_MAX];
    mode_t mode;
    uid_t uid;
    gid_t gid;
    Config config = { .euid = 1000, .egid = 1000, .umask = 0022 };
    assert(mkdtemp(directory) != NULL);
    snprintf(path, sizeof(path), "%s/a/b/record", directory);
    assert(write_meta_file(path, 06777, 1000, 1001, true, &config) == 0);
    assert(read_meta_file(path, &mode, &uid, &gid, &config) == 0);
    assert(mode == 0755 && uid == 1000 && gid == 1001);
    assert(metadata_mkdir_calls > 0);
    metadata_mkdir_calls = 0;
    assert(write_meta_file(path, 04750, 0, 0, false, &config) == 0);
    assert(read_meta_file(path, &mode, &uid, &gid, &config) == 0);
    assert(mode == 04750 && uid == 0 && gid == 0);
    assert(metadata_mkdir_calls == 0);
    FILE *record = fopen(path, "w");
    assert(record != NULL);
    assert(fputs("  2750\t1234\n5678\n\n\t", record) >= 0);
    assert(fclose(record) == 0);
    assert(read_meta_file(path, &mode, &uid, &gid, &config) == 0);
    assert(mode == 02750 && uid == 1234 && gid == 5678);
    record = fopen(path, "w");
    assert(record != NULL);
    assert(fputs("640\n321\n654", record) >= 0);
    assert(fclose(record) == 0);
    assert(read_meta_file(path, &mode, &uid, &gid, &config) == 0);
    assert(mode == 0640 && uid == 321 && gid == 654);
    assert(read_meta_file_if_present(path, &mode, &uid, &gid));
    assert(mode == 0640 && uid == 321 && gid == 654);
    record = fopen(path, "w");
    assert(record != NULL && fputs("644 123", record) >= 0 && fclose(record) == 0);
    mode = 0123;
    uid = 111;
    gid = 222;
    assert(!read_meta_file_if_present(path, &mode, &uid, &gid));
    assert(mode == 0123 && uid == 111 && gid == 222);
    assert(read_meta_file(path, &mode, &uid, &gid, &config) == 0);
    assert(mode == 0755 && uid == 1000 && gid == 1000);
    assert(write_meta_file(path, 01777, 1234, 5678, false, &config) == 0);
    assert(read_meta_file_if_present(path, &mode, &uid, &gid));
    assert(mode == 01777 && uid == 1234 && gid == 5678);
    assert(unlink(path) == 0);
    assert(!read_meta_file_if_present(path, &mode, &uid, &gid));
    assert(read_meta_file(path, &mode, &uid, &gid, &config) == 0);
    assert(mode == 0755 && uid == 1000 && gid == 1000);
    snprintf(path, sizeof(path), "%s/a/b", directory);
    assert(write_meta_file(path, 0600, 0, 0, false, &config) == -1);
    assert(errno == EISDIR);
    assert(rmdir(path) == 0);
    snprintf(path, sizeof(path), "%s/a", directory);
    assert(rmdir(path) == 0);
    assert(rmdir(directory) == 0);
}

int main(void)
{
    test_filter();
    test_metadata();
    puts("PASS filter decisions for every syscall 0..1023 on every supported ABI; metadata create/update/umask/setuid/errors");
    return 0;
}
