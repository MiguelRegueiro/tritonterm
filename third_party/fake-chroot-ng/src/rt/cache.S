/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
/* Synchronize newly written code. CTR_EL0.IDC/DIC advertise when the
 * corresponding maintenance loop is unnecessary; barriers still apply.
 * Read the kernel-visible register each call, including after migration. */
    .text
    .global cng_flush_icache
    .type cng_flush_icache, %function
cng_flush_icache:
    mrs     x3, ctr_el0
.Lsync_ctr:
#ifdef CNG_CACHE_TEST
    mov     x5, #0
    mov     x6, #0
#endif
    cmp     x0, x1
    b.hs    .Ldone
    tbnz    x3, #28, .Ldc_done
    ubfx    x4, x3, #16, #4
    mov     x2, #4
    lsl     x2, x2, x4
    sub     x4, x2, #1
    bic     x4, x0, x4
.Ldc:
    dc      cvau, x4
#ifdef CNG_CACHE_TEST
    add     x5, x5, #1
#endif
    add     x4, x4, x2
    cmp     x4, x1
    b.lo    .Ldc
.Ldc_done:
    dsb     ish
    tbnz    x3, #29, .Lic_done
    and     x4, x3, #0xf
    mov     x2, #4
    lsl     x2, x2, x4
    sub     x4, x2, #1
    bic     x4, x0, x4
.Lic:
    ic      ivau, x4
#ifdef CNG_CACHE_TEST
    add     x6, x6, #1
#endif
    add     x4, x4, x2
    cmp     x4, x1
    b.lo    .Lic
    dsb     ish
.Lic_done:
    isb
.Ldone:
#ifdef CNG_CACHE_TEST
    orr     x0, x6, x5, lsl #32
#endif
    ret
    .size cng_flush_icache, . - cng_flush_icache

#ifdef CNG_CACHE_TEST
/* Test-only entry: synthetic feature bits and loop counts. Never linked
 * into the packaged runtime; execution tests use the real register. */
    .global cng_flush_icache_test
    .type cng_flush_icache_test, %function
cng_flush_icache_test:
    mov     x3, x2
    b       .Lsync_ctr
    .size cng_flush_icache_test, . - cng_flush_icache_test
#endif
