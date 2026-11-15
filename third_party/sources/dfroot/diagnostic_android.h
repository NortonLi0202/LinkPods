/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef DFROOT_DIAGNOSTIC_ANDROID_H
#define DFROOT_DIAGNOSTIC_ANDROID_H
int dfroot_diagnostic_preflight(const char *loader, const char *module);
int dfroot_diagnostic_run(const char *loader, const char *module, const char *manager);
#endif
