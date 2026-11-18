#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

struct entry { char name[256]; unsigned long long ino; unsigned type; unsigned long long alternate; int seen; };
static struct entry expected[1024];
static int racing;
struct dent { uint64_t ino; int64_t off; unsigned short reclen; unsigned char type; char name[]; };

static void scan(int fd, size_t capacity, int count) {
    unsigned char buffer[32768];
    assert(capacity >= 32 && capacity <= sizeof buffer);
    for (int i = 0; i < count; i++) expected[i].seen = 0;
    int found = 0;
    for (;;) {
        long n = syscall(SYS_getdents64, fd, buffer, capacity);
        assert(n >= 0);
        if (!n) break;
        for (long at = 0; at < n;) {
            struct dent *d = (void *)(buffer + at);
            assert(d->reclen >= 20 && at + d->reclen <= n);
            assert(memchr(d->name, 0, d->reclen - 19));
            at += d->reclen;
            if (!strcmp(d->name, ".") || !strcmp(d->name, "..")) continue;
            int i;
            for (i = 0; i < count && strcmp(expected[i].name, d->name); i++) {}
            int match = i < count && ((expected[i].ino == d->ino && expected[i].type == d->type) ||
                (racing && d->type == DT_LNK && d->ino == expected[i].alternate));
            if (!match || expected[i].seen) {
                fprintf(stderr, "bad dirent: %s ino=%llu type=%u\n", d->name, (unsigned long long)d->ino, d->type);
                if (i < count) fprintf(stderr, "expected ino=%llu type=%u seen=%d\n", expected[i].ino, expected[i].type, expected[i].seen);
                abort();
            }
            expected[i].seen = 1;
            found++;
        }
    }
    assert(found == count);
}

static int load(const char *path) {
    FILE *manifest = fopen(path, "r");
    assert(manifest);
    int count = 0;
    char line[512];
    while (fgets(line, sizeof line, manifest)) {
        assert(count < 1024);
        expected[count].alternate = 0;
        assert(sscanf(line, "%255s %llu %u %llu", expected[count].name,
            &expected[count].ino, &expected[count].type, &expected[count].alternate) >= 3);
        count++;
    }
    assert(feof(manifest));
    fclose(manifest);
    return count;
}

int main(int argc, char **argv) {
    assert(argc >= 3);
    int count = load(argv[2]);
    int fd = open(argv[1], O_RDONLY | O_DIRECTORY);
    assert(fd >= 0);
    size_t capacity = argc > 3 ? strtoul(argv[3], 0, 10) : 32768;
    if (argc > 4 && !strcmp(argv[4], "race")) {
        racing = 1;
        for (int n = 0; n < 1000; n++) {
            assert(lseek(fd, 0, SEEK_SET) == 0);
            scan(fd, capacity, count);
        }
        close(fd);
        puts("PASS concurrent parent replacement exposes no outside inode");
        return 0;
    }
    scan(fd, capacity, count);
    if (argc > 4 && !strcmp(argv[4], "live")) {
        for (int step = 0; step < 3; step++) {
            puts("READY"); fflush(stdout);
            assert(getchar() == '\n');
            count = load(argv[2]);
            assert(lseek(fd, 0, SEEK_SET) == 0);
            scan(fd, capacity, count);
        }
    }
    /* A fresh directory-read operation must see the current view, including
     * after a cookie rewind and descriptor reuse; no fd-number cache survives. */
    assert(lseek(fd, 0, SEEK_SET) == 0);
    scan(fd, capacity, count);
    close(fd);
    fd = open(argv[1], O_RDONLY | O_DIRECTORY);
    assert(fd >= 0);
    scan(fd, capacity, count);
    close(fd);
    puts("PASS directory entries, inode/type, small buffers, rewind and descriptor reuse");
    return 0;
}
