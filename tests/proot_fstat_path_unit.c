#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifndef USERLAND
#define USERLAND
#endif
#include <assert.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-variable"
#include "extension/fake_id0/fake_id0.c"
#pragma GCC diagnostic pop

static word_t registers[NB_REG_VERSION][USERARG_1 + 1];
static unsigned char guest_memory[PATH_MAX + 32];
static unsigned reads;
static unsigned writes;
static unsigned chains;
static word_t bytes_read;
static word_t bytes_written;
static word_t destination_written;
static Sysnum chained_sysnum;
static word_t chained_args[6];
static int read_error;
static int write_error;

word_t peek_reg(const Tracee *tracee, RegVersion version, Reg reg)
{
    (void) tracee;
    assert(version < NB_REG_VERSION && reg <= USERARG_1);
    return registers[version][reg];
}

void poke_reg(Tracee *tracee, Reg reg, word_t value)
{
    (void) tracee;
    assert(reg == SYSARG_RESULT);
    registers[CURRENT][reg] = value;
}

int read_data(const Tracee *tracee, void *destination, word_t source, word_t size)
{
    (void) tracee;
    reads++;
    bytes_read += size;
    assert(source == (word_t) (guest_memory + 16));
    assert(size < PATH_MAX);
    if (read_error)
        return read_error;
    memcpy(destination, (const void *) source, size);
    return 0;
}

int write_data(Tracee *tracee, word_t destination, const void *source, word_t size)
{
    (void) tracee;
    writes++;
    bytes_written += size;
    destination_written = destination;
    assert(size == 1 && *(const char *) source == '\0');
    assert(destination >= (word_t) (guest_memory + 16));
    assert(destination < (word_t) (guest_memory + sizeof(guest_memory)));
    if (write_error)
        return write_error;
    memcpy((void *) destination, source, size);
    return 0;
}

int register_chained_syscall(Tracee *tracee, Sysnum sysnum,
                            word_t arg1, word_t arg2, word_t arg3,
                            word_t arg4, word_t arg5, word_t arg6)
{
    (void) tracee;
    chains++;
    chained_sysnum = sysnum;
    chained_args[0] = arg1;
    chained_args[1] = arg2;
    chained_args[2] = arg3;
    chained_args[3] = arg4;
    chained_args[4] = arg5;
    chained_args[5] = arg6;
    return 0;
}

static Tracee reset_case(const char *path, word_t result)
{
    memset(registers, 0, sizeof(registers));
    memset(guest_memory, 0xa5, sizeof(guest_memory));
    if (path) {
        size_t length = strlen(path);
        assert(length <= PATH_MAX);
        memcpy(guest_memory + 16, path, length);
    }
    registers[CURRENT][SYSARG_RESULT] = result;
    registers[ORIGINAL][SYSARG_1] = 42;
    registers[ORIGINAL][SYSARG_2] = 0x12345678;
    registers[MODIFIED][SYSARG_3] = (word_t) (guest_memory + 16);
    reads = writes = chains = 0;
    bytes_read = bytes_written = destination_written = 0;
    read_error = write_error = 0;
    memset(chained_args, 0, sizeof(chained_args));
    Tracee tracee = {0};
    return tracee;
}

static void assert_normal_path(const char *path)
{
    size_t length = strlen(path);
    Tracee tracee = reset_case(path, length);
    assert(handle_fstat_readlink_exit(&tracee, PR_fstat) == 0);
    assert(registers[CURRENT][SYSARG_RESULT] == 0);
    assert(reads == 1 && bytes_read == length);
    assert(writes == 1 && bytes_written == 1);
    assert(destination_written == (word_t) (guest_memory + 16 + length));
    assert(memcmp(guest_memory + 16, path, length) == 0);
    assert(guest_memory[16 + length] == '\0');
    assert(guest_memory[15] == 0xa5 && guest_memory[17 + length] == 0xa5);
    assert(chains == 1);
#if defined(__x86_64__)
    assert(chained_sysnum == PR_newfstatat);
#else
    assert(chained_sysnum == PR_fstatat64);
#endif
    assert(chained_args[0] == (word_t) AT_FDCWD);
    assert(chained_args[1] == (word_t) (guest_memory + 16));
    assert(chained_args[2] == 0x12345678);
    assert(chained_args[3] == 0 && chained_args[4] == 0 && chained_args[5] == 0);
}

static void test_normal_paths(void)
{
    assert_normal_path("/guest/usr/file");
    assert_normal_path("/bound/cache/file");
    assert_normal_path("/");
    assert_normal_path("/x");
    assert_normal_path("/directory (deleted)/file");
    char longest_path[PATH_MAX];
    memset(longest_path, 'a', sizeof(longest_path) - 1);
    longest_path[0] = '/';
    longest_path[sizeof(longest_path) - 1] = '\0';
    assert_normal_path(longest_path);
}

static void test_fd_fallbacks(void)
{
    const char *paths[] = {"/guest/file (deleted)", "pipe:[1234]", "pipe"};
    const Sysnum sysnums[] = {PR_fstat, PR_fstat64};
    for (size_t p = 0; p < sizeof(paths) / sizeof(paths[0]); p++) {
        for (size_t s = 0; s < sizeof(sysnums) / sizeof(sysnums[0]); s++) {
            size_t length = strlen(paths[p]);
            Tracee tracee = reset_case(paths[p], length);
            assert(handle_fstat_readlink_exit(&tracee, sysnums[s]) == 0);
            assert(reads == 1 && bytes_read == length && writes == 0);
            assert(guest_memory[16 + length] == 0xa5);
            assert(chains == 1 && chained_sysnum == sysnums[s]);
            assert(chained_args[0] == 42 && chained_args[1] == 0x12345678);
            for (unsigned i = 2; i < 6; i++)
                assert(chained_args[i] == 0);
        }
    }
}

static void test_errors(void)
{
    Tracee tracee = reset_case(NULL, 0);
    assert(handle_fstat_readlink_exit(&tracee, PR_fstat) == 0);
    assert(reads == 0 && writes == 0 && chains == 0);

    tracee = reset_case(NULL, (word_t) -EBADF);
    assert(handle_fstat_readlink_exit(&tracee, PR_fstat) == -EBADF);
    assert(registers[CURRENT][SYSARG_RESULT] == 0);
    assert(reads == 0 && writes == 0 && chains == 0);

    tracee = reset_case(NULL, PATH_MAX);
    assert(handle_fstat_readlink_exit(&tracee, PR_fstat) == -ENAMETOOLONG);
    assert(reads == 0 && writes == 0 && chains == 0);

    tracee = reset_case(NULL, PATH_MAX + 1);
    assert(handle_fstat_readlink_exit(&tracee, PR_fstat) == -ENAMETOOLONG);
    assert(reads == 0 && writes == 0 && chains == 0);

    tracee = reset_case("/regular", strlen("/regular"));
    read_error = -EFAULT;
    assert(handle_fstat_readlink_exit(&tracee, PR_fstat) == -EFAULT);
    assert(reads == 1 && writes == 0 && chains == 0);

    tracee = reset_case("/regular", strlen("/regular"));
    write_error = -EFAULT;
    assert(handle_fstat_readlink_exit(&tracee, PR_fstat) == -EFAULT);
    assert(reads == 1 && writes == 1 && bytes_written == 1 && chains == 0);
}

int main(void)
{
    test_normal_paths();
    test_fd_fallbacks();
    test_errors();
    puts("PASS fstat exact-length reads, one-byte terminator, fd fallbacks, bounds and memory errors");
    return 0;
}
