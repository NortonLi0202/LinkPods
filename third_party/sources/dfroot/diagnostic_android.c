/* SPDX-License-Identifier: GPL-2.0-only */
#define _GNU_SOURCE
#include "diagnostic_android.h"
#include "diagnostic_flow.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef CAPTURE_MS
#define CAPTURE_MS 30000
#endif
#ifndef CAPTURE_LIMIT
#define CAPTURE_LIMIT (1024U * 1024U)
#endif
#ifndef KSUD_PATH
#define KSUD_PATH "/data/adb/ksud"
#endif
struct diagnostic {
    const char *loader, *module, *manager;
    char directory[PATH_MAX];
    int loader_fd, status_fd, capture_error;
    pid_t collector, logcat;
};

static int full_write(int fd, const void *data, size_t size) {
    const char *p = data;
    while (size) {
        ssize_t n = write(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return n < 0 ? errno : EIO;
        p += n; size -= (size_t)n;
    }
    return 0;
}

static long long monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return -1;
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int wait_child(pid_t pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return errno;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

static void stop_child(pid_t pid) {
    if (pid <= 0) return;
    kill(pid, SIGTERM);
    for (int i = 0; i < 40; ++i) {
        pid_t result = waitpid(pid, NULL, WNOHANG);
        if (result == pid || (result < 0 && errno == ECHILD)) return;
        usleep(50000);
    }
    kill(pid, SIGKILL);
    (void)wait_child(pid);
}

int dfroot_diagnostic_preflight(const char *loader, const char *module) {
    if (getuid() != 0) return EACCES;
    char context[128] = {0};
    int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return errno;
    ssize_t n = read(fd, context, sizeof(context) - 1);
    int saved = errno; close(fd);
    if (n <= 0) return n < 0 ? saved : EIO;
    context[strcspn(context, "\n")] = '\0';
    if (strcmp(context, "u:r:init:s0")) return EACCES;
    FILE *modules = fopen("/proc/modules", "re");
    if (!modules) return errno;
    char line[512], name[128];
    int error = 0;
    while (fgets(line, sizeof(line), modules)) {
        if (sscanf(line, "%127s", name) == 1 && !strcmp(name, "kernelsu")) {
            error = EEXIST; break;
        }
    }
    if (ferror(modules)) error = EIO;
    fclose(modules);
    if (error) return error;
    struct stat st;
    if (!loader || !module || loader[0] != '/' || module[0] != '/') return EINVAL;
    if (stat(loader, &st) || !S_ISREG(st.st_mode) || access(loader, X_OK)) return EACCES;
    if (stat(module, &st) || !S_ISREG(st.st_mode) || st.st_size <= 0) return EINVAL;
    return 0;
}

static int check_environment(void *opaque) {
    struct diagnostic *d = opaque;
    return dfroot_diagnostic_preflight(d->loader, d->module);
}

static int log_file(struct diagnostic *d, const char *name) {
    char path[PATH_MAX];
    if (snprintf(path, sizeof(path), "%s/%s", d->directory, name) >= (int)sizeof(path)) {
        errno = ENAMETOOLONG; return -1;
    }
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
    if (fd >= 0 && fchmod(fd, 0644)) {
        int saved = errno; close(fd); errno = saved; return -1;
    }
    return fd;
}

static int bounded_write(int fd, const char *data, size_t n, size_t *used) {
    size_t available = CAPTURE_LIMIT - *used;
    size_t count = n < available ? n : available;
    int error = full_write(fd, data, count);
    if (error) return error;
    *used += count;
    return n > count ? EFBIG : 0;
}

static int collect(int kernel, int userspace, int kernel_out, int user_out, int ready) {
    long long start = monotonic_ms();
    if (start < 0) return EIO;
    /* Validate that the kernel reader works before announcing readiness.
     * No module has been invoked, so pre-ready records are not candidate logs. */
    char probe[8192];
    ssize_t probe_size;
    do { probe_size = read(kernel, probe, sizeof(probe)); } while (probe_size < 0 && errno == EINTR);
    if (probe_size == 0 || (probe_size < 0 && errno != EAGAIN)) return probe_size == 0 ? EIO : errno;
    if (full_write(ready, "R", 1)) return EIO;
    close(ready);
    struct pollfd streams[2] = {{kernel, POLLIN, 0}, {userspace, POLLIN, 0}};
    size_t used[2] = {0, 0};
    int result = 0;
    char buffer[8192];
    for (;;) {
        long long now = monotonic_ms();
        if (now < 0) return EIO;
        if (now - start >= CAPTURE_MS) break;
        int timeout = (int)(CAPTURE_MS - (now - start));
        if (timeout > 250) timeout = 250;
        int count = poll(streams, 2, timeout);
        if (count < 0) { if (errno == EINTR) continue; return errno; }
        for (int i = 0; i < 2; ++i) {
            if (streams[i].fd < 0 || !streams[i].revents) continue;
            ssize_t n = read(streams[i].fd, buffer, sizeof(buffer) - 1);
            if (n < 0) {
                if (errno == EAGAIN || errno == EINTR) continue;
                if (i == 0 && errno == EPIPE) { result = EOVERFLOW; continue; }
                return errno;
            }
            if (n == 0) {
                if (i == 0) return EIO;
                streams[i].fd = -1; result = result ? result : EIO; continue;
            }
            buffer[n] = '\0';
            if (i == 0 && !strstr(buffer, "KernelSU") && !strstr(buffer, "ksudbg:") &&
                !strstr(buffer, "sepolicy:") && !strstr(buffer, "SELinux:")) continue;
            int error = bounded_write(i == 0 ? kernel_out : user_out, buffer, (size_t)n, &used[i]);
            if (error && error != EFBIG) return error;
            if (error) result = error;
        }
    }
    return result;
}

static int capture_start(void *opaque) {
    struct diagnostic *d = opaque;
    int kernel = -1, kernel_out = -1, user_out = -1;
    int logs[2] = {-1, -1}, errors[2] = {-1, -1}, ready[2] = {-1, -1};
    int error = 0;
    kernel = open("/dev/kmsg", O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (kernel < 0) return errno;
    if (lseek(kernel, 0, SEEK_END) < 0) { error = errno; goto fail; }
    strcpy(d->directory, "/data/local/tmp/dfroot-diag-XXXXXX");
    if (!mkdtemp(d->directory) || chmod(d->directory, 0755)) { error = errno; goto fail; }
    kernel_out = log_file(d, "kernel.log"); user_out = log_file(d, "userspace.log");
    d->loader_fd = log_file(d, "loader.log"); d->status_fd = log_file(d, "status.log");
    if (kernel_out < 0 || user_out < 0 || d->loader_fd < 0 || d->status_fd < 0) {
        error = errno; goto fail;
    }
    if (pipe2(logs, O_CLOEXEC) || pipe2(errors, O_CLOEXEC) || pipe2(ready, O_CLOEXEC)) {
        error = errno; goto fail;
    }
    d->logcat = fork();
    if (d->logcat < 0) { error = errno; goto fail; }
    if (d->logcat == 0) {
        close(errors[0]); close(logs[0]);
        if (dup2(logs[1], STDOUT_FILENO) < 0 || dup2(logs[1], STDERR_FILENO) < 0) {
            int e = errno; (void)full_write(errors[1], &e, sizeof(e)); _exit(127);
        }
        execl("/system/bin/logcat", "logcat", "-b", "main", "-b", "system", "-v", "threadtime",
              "-T", "1", "KernelSU:V", "*:S", (char *)NULL);
        int e = errno; (void)full_write(errors[1], &e, sizeof(e)); _exit(127);
    }
    close(logs[1]); logs[1] = -1; close(errors[1]); errors[1] = -1;
    int exec_error = 0; ssize_t n;
    do { n = read(errors[0], &exec_error, sizeof(exec_error)); } while (n < 0 && errno == EINTR);
    if (n != 0) { error = n > 0 ? exec_error : errno; goto fail; }
    close(errors[0]); errors[0] = -1;
    d->collector = fork();
    if (d->collector < 0) { error = errno; goto fail; }
    if (d->collector == 0) {
        close(ready[0]); close(d->loader_fd); close(d->status_fd);
        int rc = collect(kernel, logs[0], kernel_out, user_out, ready[1]);
        _exit(rc > 0 && rc < 126 ? rc : (rc ? 125 : 0));
    }
    close(ready[1]); ready[1] = -1;
    char marker;
    do { n = read(ready[0], &marker, 1); } while (n < 0 && errno == EINTR);
    if (n != 1 || marker != 'R') { error = EIO; goto fail; }
    dprintf(d->status_fd, "capture_ready window_ms=%d byte_limit_per_stream=%u directory=%s\n",
            CAPTURE_MS, CAPTURE_LIMIT, d->directory);
    fprintf(stderr, "Diagnostic capture: %s\n", d->directory);
    close(kernel); close(kernel_out); close(user_out); close(logs[0]); close(ready[0]);
    return 0;
fail:
    stop_child(d->collector); stop_child(d->logcat); d->collector = d->logcat = -1;
    int *pipes[] = {logs, errors, ready};
    for (int i = 0; i < 3; ++i) for (int j = 0; j < 2; ++j) if (pipes[i][j] >= 0) close(pipes[i][j]);
    if (kernel >= 0) close(kernel);
    if (kernel_out >= 0) close(kernel_out);
    if (user_out >= 0) close(user_out);
    if (d->status_fd >= 0) { dprintf(d->status_fd, "capture_start_error=%d\n", error); close(d->status_fd); }
    if (d->loader_fd >= 0) close(d->loader_fd);
    d->status_fd = d->loader_fd = -1;
    return error ? error : EIO;
}

static int run_loader(struct diagnostic *d, char *const argv[]) {
    pid_t pid = fork();
    if (pid < 0) return errno;
    if (pid == 0) {
        if (dup2(d->loader_fd, STDOUT_FILENO) < 0 || dup2(d->loader_fd, STDERR_FILENO) < 0) _exit(126);
        execv(argv[0], argv);
        dprintf(d->status_fd, "exec_failed path=%s errno=%d\n", argv[0], errno);
        _exit(127);
    }
    return wait_child(pid);
}

static int validate_context(const char *context) {
    int fd = open("/sys/fs/selinux/context", O_WRONLY | O_CLOEXEC);
    if (fd < 0) return errno;
    /* A single validation transaction; never writes process attr/current or policy. */
    ssize_t n = write(fd, context, strlen(context));
    int error = n < 0 ? errno : ((size_t)n == strlen(context) ? 0 : EIO);
    close(fd); return error;
}

static int capture_alive(struct diagnostic *d) {
    pid_t *children[] = {&d->collector, &d->logcat};
    for (int i = 0; i < 2; ++i) {
        int status;
        pid_t p;
        if (*children[i] <= 0) return EIO;
        do { p = waitpid(*children[i], &status, WNOHANG); } while (p < 0 && errno == EINTR);
        if (p != 0) {
            int wait_error = p < 0 ? errno : 0;
            int error = p > 0 ? (WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status)) : wait_error;
            dprintf(d->status_fd, "early_capture_child=%s status=%d\n", i == 0 ? "collector" : "logcat", error);
            if (p > 0 || wait_error == ECHILD) *children[i] = -1;
            d->capture_error = error ? error : EIO;
            return d->capture_error;
        }
    }
    return 0;
}

static int load_candidate(void *opaque) {
    struct diagnostic *d = opaque;
    int capture_status = capture_alive(d);
    if (capture_status) return capture_status;
    struct stat st;
    if (stat(d->module, &st)) return errno;
    dprintf(d->status_fd, "candidate=%s bytes=%lld hash_verified_by_app_staging_only\n", d->module, (long long)st.st_size);
    int rc = run_loader(d, (char *[]){(char *)d->loader, "insmod", (char *)d->module, NULL});
    dprintf(d->status_fd, "insmod_exit=%d\n", rc);
    if (rc) return rc;
    const char *contexts[] = {"u:r:ksu:s0", "u:object_r:ksu_file:s0"};
    for (int i = 0; i < 2; ++i) {
        rc = validate_context(contexts[i]);
        /* EACCES means this process may not call security:check_context; the
         * result then says nothing about the label. A missing type returns
         * EINVAL and still aborts before late-load. */
        dprintf(d->status_fd, "context=%s validation_error=%d%s\n", contexts[i], rc,
                rc == EACCES ? " unverifiable=check_context" : "");
        if (rc && rc != EACCES) return rc;
    }
    return 0;
}

static void preclean_link(int fd, const char *path) {
    struct stat st;
    if (lstat(path, &st)) {
        dprintf(fd, "preclean path=%s state=absent errno=%d\n", path, errno);
        return;
    }
    const char *type = S_ISLNK(st.st_mode) ? "symlink" : S_ISDIR(st.st_mode) ? "dir" : "file";
    if (S_ISDIR(st.st_mode)) {
        dprintf(fd, "preclean path=%s state=dir action=kept\n", path);
        return;
    }
    int rc = unlink(path);
    dprintf(fd, "preclean path=%s state=%s action=unlink rc=%d\n", path, type, rc);
}

static int start_late_load(void *opaque) {
    struct diagnostic *d = opaque;
    int capture_status = capture_alive(d);
    if (capture_status) return capture_status;
    /* ksud's installer removes and recreates its own links, but a dangling
     * link makes its existence check fail and the symlink then returns EEXIST.
     * Clear only those installer-owned link paths first. */
    preclean_link(d->status_fd, "/data/adb/ksu/bin/resetprop");
    preclean_link(d->status_fd, "/data/adb/ksu/bin/ksud");
    /* The module's own insmod installs /data/adb/ksud, which carries the label
     * init is allowed to execute after the policy switch. The staged copy in
     * app data is the fallback. The nonexistent KMI selector keeps a failed
     * detection from loading the embedded stock module. */
    struct stat st;
    const char *late = KSUD_PATH;
    struct stat kst;
    if (lstat(KSUD_PATH, &kst) == 0)
        dprintf(d->status_fd, "ksud_path type=%s mode=%o\n",
                S_ISREG(kst.st_mode) ? "file" : S_ISDIR(kst.st_mode) ? "dir" :
                S_ISLNK(kst.st_mode) ? "symlink" : "other", (unsigned)kst.st_mode & 07777);
    if (stat(late, &st) || !S_ISREG(st.st_mode) || access(late, X_OK)) late = d->loader;
    struct stat staged;
    if (stat(d->loader, &staged) == 0)
        dprintf(d->status_fd, "staged_loader mode=%o bytes=%lld\n",
                (unsigned)staged.st_mode & 07777, (long long)staged.st_size);
    dprintf(d->status_fd, "late_loader=%s\n", late);
    int rc = run_loader(d, (char *[]){(char *)late, "late-load", "--kmi",
        "diagnostic-external-only", "--package-name", (char *)d->manager, NULL});
    if (rc == 127 && late != d->loader) {
        dprintf(d->status_fd, "late_loader_fallback=%s\n", d->loader);
        rc = run_loader(d, (char *[]){(char *)d->loader, "late-load", "--kmi",
            "diagnostic-external-only", "--package-name", (char *)d->manager, NULL});
    }
    int ksud_present = stat(KSUD_PATH, &st) == 0 && S_ISREG(st.st_mode);
    dprintf(d->status_fd, "late_load_launcher_exit=%d ksud_present=%d root_verified=false\n",
            rc, ksud_present);
    return rc;
}

static int capture_finish(void *opaque) {
    struct diagnostic *d = opaque;
    int rc = d->collector > 0 ? wait_child(d->collector) : d->capture_error;
    if (!rc) rc = d->capture_error;
    d->collector = -1; stop_child(d->logcat); d->logcat = -1;
    dprintf(d->status_fd, "capture_exit=%d root_verified=false\n", rc);
    close(d->status_fd); close(d->loader_fd); d->status_fd = d->loader_fd = -1;
    return rc;
}

int dfroot_diagnostic_run(const char *loader, const char *module, const char *manager) {
    struct diagnostic d = {.loader = loader, .module = module, .manager = manager,
        .loader_fd = -1, .status_fd = -1, .collector = -1, .logcat = -1};
    if (!manager || !*manager) return EINVAL;
    const struct df_diag_ops ops = {check_environment, capture_start, load_candidate, start_late_load, capture_finish};
    return df_diag_run(&ops, &d);
}
