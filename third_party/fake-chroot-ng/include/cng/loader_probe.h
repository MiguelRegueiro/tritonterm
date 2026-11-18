/* SPDX-License-Identifier: Apache-2.0 */
#ifndef CNG_LOADER_PROBE_H
#define CNG_LOADER_PROBE_H
/* Diagnostic only: raw host mappings, no execmap fallback. Stage 9 is success.
 * Failure stages: 1 memfd, 2 size, 3 fill, 4 seals, 5 private map,
 * 6 RX transition, 7 private execution, 8 original execution, 10 seal check.
 * Parent stages: 11 pipe, 12 fork, 13 wait, 14 timeout, 15 child signal,
 * 16 incomplete result. No image is retained or used to load a guest. */
struct cng_loader_probe_result { long stage, error, signal; };
struct cng_loader_probe_result cng_loader_probe(void);
#endif
