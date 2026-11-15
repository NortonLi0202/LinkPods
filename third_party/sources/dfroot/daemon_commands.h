#ifndef HANDOFF_DAEMON_COMMANDS_H
#define HANDOFF_DAEMON_COMMANDS_H
#include <string.h>

/* Exact tokens only. No caller-supplied text is interpolated into a shell. */
static inline const char *daemon_fixed_command(const char *request) {
    if (!strcmp(request, "id")) return "/system/bin/id";
    if (!strcmp(request, "getenforce")) return "/system/bin/getenforce";
    if (!strcmp(request, "id -u; id -Z")) return "/system/bin/id -u; /system/bin/id -Z";
    if (!strcmp(request, "bridge-status")) return "/system/bin/cat /data/misc/bluedroid/handoff-runtime.status";
    if (!strcmp(request, "bridge-inject")) return "/system/bin/sh /data/adb/handoff/inject-bluetooth.sh";
    if (!strcmp(request, "bridge-stop")) return "printf 'stop\\n' > /data/misc/bluedroid/handoff-runtime.control";
    return NULL;
}
#endif
