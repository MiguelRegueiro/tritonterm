#ifndef CNG_TRITONTERM_H
#define CNG_TRITONTERM_H

#include "cng/path.h"

struct cng_triton_record {
    unsigned mode;
    unsigned uid;
    unsigned gid;
};

struct cng_triton_change {
    int op;
    char source[CNG_PATH_MAX];
    char target[CNG_PATH_MAX];
    struct cng_triton_record record;
};

int cng_triton_enabled(void);
int cng_triton_read(const char *host, struct cng_triton_record *record);
void cng_triton_fd_stat(long fd, void *buf, int statx);
void cng_triton_stat(long dirfd, const char *host, void *st, int statx);
long cng_triton_move_record(const char *source, const char *target);
void cng_triton_remove_record(const char *host);
long cng_triton_access(const char *host, int mode, int real);
long cng_triton_exec_check(const char *host);
long cng_triton_open_before(struct cng_triton_change *change, long dirfd,
                              const char *host, long flags, int follow);
long cng_triton_before(struct cng_triton_change *change, long nr, long a0,
                       long a1, long a2, long a3, long a4);
long cng_triton_after(struct cng_triton_change *change, long result);

#endif
