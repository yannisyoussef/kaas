/*
 * The trusted source bootstrap.
 *
 * WHAT THIS EXISTS FOR
 *
 * KAAS-18 measured that a host bind mount reaches a gVisor sandbox over the gofer as a 9p mount carrying
 * `ro` and nothing else: `noexec`, `nosuid` and `nodev` are requested and dropped, and a shebang script
 * placed on that mount executed. Execution was refused only because the materialiser happens to write files
 * without an executable bit. That is a property of the file, not of the filesystem, and the two are not the
 * same claim.
 *
 * A sandbox-internal tmpfs does honour those flags under gVisor -- measured, both directions. But a tmpfs is
 * created empty and cannot be populated from the host, and a tmpfs declared read-only is empty forever. So
 * something inside the sandbox has to put the bytes there and then close the filesystem behind itself. That
 * is this program, and it is the whole reason it exists.
 *
 * WHAT IT IS ALLOWED TO DO
 *
 * Receive a platform-framed bundle on standard input, enforce bounds, create platform-named directories,
 * write regular files at a platform-chosen mode, write a platform-generated manifest, make the filesystem
 * read-only, drop every capability, become the unprivileged sandbox user, and exec the verifier.
 *
 * WHAT IT MUST NEVER DO
 *
 * Run a shell. Run an interpreter. Take a path, a mode, a mount option, a target or a command from the
 * stream. Put a byte of tenant source into argv, into an environment variable, or into any log line. Every
 * privileged operation in this file takes compile-time constants only: no value read from stdin reaches
 * mount(), execv(), or any decision about them.
 *
 * WHY C AND WHY STATIC
 *
 * It runs before anything else in the sandbox and it must not depend on a runtime, an interpreter or a
 * dynamic loader that could be influenced. It is built from this file alone, in a pinned build stage, into
 * the pinned probe image.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <grp.h>
#include <unistd.h>

/* Platform constants. None of these is ever read from the stream. */
#define SOURCE_ROOT      "/kaas/source"
#define FILES_DIR        "/kaas/source/files"
#define MANIFEST_PATH    "/kaas/source/manifest.tsv"
#define FORMAT_VERSION   "kaas.source-bundle.v1"
#define VERIFIER         "/probe.sh"
#define SHELL            "/bin/sh"
/* The verifier modes this build will hand over to. A closed list checked by exact comparison, so the word
 * that reaches the verifier is one of these two literals and never a string that arrived from anywhere. */
#define VERIFIER_MODE    "sourceverify"
#define BOUNDARY_MODE    "sourceboundary"
#define SANDBOX_UID      65534
#define SANDBOX_GID      65534

/* Bounds, mirroring packages/api-contracts/source-bundle.json. The runner enforces these too; a bootstrap
 * that trusted the runner would be one defect away from unbounded writes into a fixed-size filesystem. */
#define MAX_ENTRIES      1000
#define MAX_ENTRY_BYTES  (1024L * 1024L)
#define MAX_TOTAL_BYTES  (64L * 1024L * 1024L)
#define MAX_PATH_LENGTH  512

#define FRAME_MAGIC      "KAASSRC1"
#define FRAME_END        "KAASEND1"
#define DIGEST_HEX_LEN   64

/*
 * Failure reporting.
 *
 * A category and nothing else. No path, no byte of content, no length that could distinguish one tenant's
 * bundle from another's. The verifier's own output is the only channel that describes the source, and it
 * describes it as digests.
 */
static void fail(const char *category) {
    fprintf(stdout, "bootstrap_outcome=FAILED\n");
    fprintf(stdout, "bootstrap_failure=%s\n", category);
    fflush(stdout);
    _exit(0); /* Zero: a refused bundle is a reported outcome, not a crashed container. */
}

static void emit(const char *key, const char *value) {
    fprintf(stdout, "%s=%s\n", key, value);
    fflush(stdout);
}

/* ------------------------------------------------------------------ reading */

/* Reads exactly n bytes or fails. Short reads on a pipe are normal and are not an error. */
static void read_exact(void *into, size_t n, const char *category) {
    unsigned char *p = (unsigned char *) into;
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(STDIN_FILENO, p + got, n - got);
        if (r == 0) {
            fail(category);
        }
        if (r < 0) {
            if (errno == EINTR) {
                continue;
            }
            fail(category);
        }
        got += (size_t) r;
    }
}

static uint32_t read_u32(void) {
    unsigned char b[4];
    read_exact(b, sizeof(b), "TRUNCATED");
    return ((uint32_t) b[0] << 24) | ((uint32_t) b[1] << 16) | ((uint32_t) b[2] << 8) | (uint32_t) b[3];
}

static uint64_t read_u64(void) {
    unsigned char b[8];
    read_exact(b, sizeof(b), "TRUNCATED");
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) {
        v = (v << 8) | (uint64_t) b[i];
    }
    return v;
}

/* ------------------------------------------------------------------ paths */

/*
 * The path rules, applied to a path the platform authored.
 *
 * They are applied anyway. The control plane checked them when the bundle was assembled and the runner
 * checked them again before framing it; this is the last place before a filesystem call, and a defect
 * upstream must not become a write outside the source root.
 */
static int path_is_safe(const char *path, size_t len) {
    if (len == 0 || len > MAX_PATH_LENGTH) {
        return 0;
    }
    if (path[0] == '/' || path[len - 1] == '/') {
        return 0;
    }
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char) path[i];
        if (c == 0 || c < 0x20 || c == 0x7f || c == '\\') {
            return 0;
        }
        if (c == '/' && i + 1 < len && path[i + 1] == '/') {
            return 0;
        }
    }
    /* No segment may be "." or "..". Checked segment by segment rather than by substring search, so a name
     * like "..foo" is accepted and a segment ".." is not. */
    size_t start = 0;
    for (size_t i = 0; i <= len; i++) {
        if (i == len || path[i] == '/') {
            size_t seg = i - start;
            if (seg == 0) {
                return 0;
            }
            if (seg == 1 && path[start] == '.') {
                return 0;
            }
            if (seg == 2 && path[start] == '.' && path[start + 1] == '.') {
                return 0;
            }
            start = i + 1;
        }
    }
    return 1;
}

/* Creates every parent directory of a relative path under the files root. Platform mode, never a stream one. */
static void make_parents(char *absolute) {
    for (char *p = absolute + strlen(FILES_DIR) + 1; *p; p++) {
        if (*p != '/') {
            continue;
        }
        *p = '\0';
        if (mkdir(absolute, 0755) != 0 && errno != EEXIST) {
            fail("STAGING");
        }
        *p = '/';
    }
}

/* ------------------------------------------------------------------ privilege */

/*
 * Drops every capability, irreversibly, and becomes the unprivileged sandbox user.
 *
 * Order matters and is the reason this is one function rather than three call sites. The bounding set is
 * cleared first so nothing can be regained by exec; no-new-privs is set so a setuid binary cannot raise
 * privilege across the exec that follows; the group is dropped before the user, because dropping the user
 * first would remove the privilege needed to drop the group.
 *
 * Nothing here is reported as evidence. The verifier reads the resulting state out of /proc after the exec,
 * because "the bootstrap called capset" is a statement about a call and not about a process.
 */
static void drop_all_privilege(void) {
    for (int cap = 0; cap <= 63; cap++) {
        /* EINVAL means the kernel does not know that capability number, which is not a failure. */
        if (prctl(PR_CAPBSET_DROP, cap, 0, 0, 0) != 0 && errno != EINVAL && errno != EPERM) {
            fail("PRIVILEGE_DROP");
        }
    }
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        fail("PRIVILEGE_DROP");
    }
    /* The identity transition, and only when there is one to make.
     *
     * The launcher starts this process as the sandbox user already, so ordinarily there is nothing to change
     * and asking for the change would mean asking for CAP_SETUID and CAP_SETGID that the construction phase
     * does not otherwise need. Requesting privilege in order to give it up is a bad trade, so the transition
     * runs only in the case where the process really is somebody else. */
    if (getuid() != SANDBOX_UID || getgid() != SANDBOX_GID) {
        if (setgroups(0, NULL) != 0 && errno != EPERM) {
            fail("PRIVILEGE_DROP");
        }
        if (setgid(SANDBOX_GID) != 0) {
            fail("PRIVILEGE_DROP");
        }
        if (setuid(SANDBOX_UID) != 0) {
            fail("PRIVILEGE_DROP");
        }
    }
    /* Belt and braces on the permitted/effective/inheritable sets. After setuid from root these are already
     * cleared, but this program must not depend on that being true of every kernel it meets. */
    struct __user_cap_header_struct header;
    struct __user_cap_data_struct data[2];
    memset(&header, 0, sizeof(header));
    memset(data, 0, sizeof(data));
    header.version = _LINUX_CAPABILITY_VERSION_3;
    header.pid = 0;
    if (syscall(SYS_capset, &header, data) != 0 && errno != EPERM) {
        fail("PRIVILEGE_DROP");
    }
    /* And prove the caller cannot climb back. Reported as a failure rather than trusted, because a process
     * that can still become root has not dropped anything -- it has merely stopped using what it holds. */
    if (setuid(0) == 0) {
        fail("PRIVILEGE_RETAINED");
    }
}

/* ------------------------------------------------------------------ main */

int main(int argc, char **argv) {
    /* The source root exists as an empty tmpfs mounted by the launcher. Nothing here creates or chooses it. */
    if (mkdir(FILES_DIR, 0755) != 0 && errno != EEXIST) {
        fail("STAGING");
    }

    char magic[8];
    read_exact(magic, sizeof(magic), "TRUNCATED");
    if (memcmp(magic, FRAME_MAGIC, sizeof(magic)) != 0) {
        fail("MALFORMED");
    }

    char bundle_digest[DIGEST_HEX_LEN + 1];
    read_exact(bundle_digest, DIGEST_HEX_LEN, "TRUNCATED");
    bundle_digest[DIGEST_HEX_LEN] = '\0';
    for (int i = 0; i < DIGEST_HEX_LEN; i++) {
        char c = bundle_digest[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            fail("MALFORMED");
        }
    }

    uint32_t count = read_u32();
    if (count == 0 || count > MAX_ENTRIES) {
        fail("TOO_LARGE");
    }

    FILE *manifest = fopen(MANIFEST_PATH, "w");
    if (manifest == NULL) {
        fail("STAGING");
    }
    fprintf(manifest, "%s\tsha256:%s\t%u\n", FORMAT_VERSION, bundle_digest, count);

    static char buffer[MAX_ENTRY_BYTES];
    uint64_t total = 0;

    for (uint32_t i = 0; i < count; i++) {
        uint32_t path_len = read_u32();
        if (path_len == 0 || path_len > MAX_PATH_LENGTH) {
            fail("UNSAFE_PATH");
        }
        char relative[MAX_PATH_LENGTH + 1];
        read_exact(relative, path_len, "TRUNCATED");
        relative[path_len] = '\0';
        if (!path_is_safe(relative, path_len)) {
            fail("UNSAFE_PATH");
        }

        char digest[DIGEST_HEX_LEN + 1];
        read_exact(digest, DIGEST_HEX_LEN, "TRUNCATED");
        digest[DIGEST_HEX_LEN] = '\0';
        for (int d = 0; d < DIGEST_HEX_LEN; d++) {
            char c = digest[d];
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                fail("MALFORMED");
            }
        }

        uint64_t size = read_u64();
        if (size > (uint64_t) MAX_ENTRY_BYTES) {
            fail("TOO_LARGE");
        }
        total += size;
        if (total > (uint64_t) MAX_TOTAL_BYTES) {
            fail("TOO_LARGE");
        }
        if (size > 0) {
            read_exact(buffer, (size_t) size, "TRUNCATED");
        }

        char absolute[sizeof(FILES_DIR) + MAX_PATH_LENGTH + 2];
        snprintf(absolute, sizeof(absolute), "%s/%s", FILES_DIR, relative);
        make_parents(absolute);

        /* O_CREAT|O_EXCL|O_NOFOLLOW: nothing existing is opened, and no link is followed. Mode 0444 at
         * creation rather than by a later chmod, so the file is never briefly writable. */
        int fd = open(absolute, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0444);
        if (fd < 0) {
            fail("STAGING");
        }
        size_t written = 0;
        while (written < (size_t) size) {
            ssize_t w = write(fd, buffer + written, (size_t) size - written);
            if (w < 0) {
                if (errno == EINTR) {
                    continue;
                }
                close(fd);
                fail("STAGING");
            }
            written += (size_t) w;
        }
        if (close(fd) != 0) {
            fail("STAGING");
        }
        /* The mode is set again after closing because some filesystems apply a umask to the creation mode. */
        if (chmod(absolute, 0444) != 0) {
            fail("STAGING");
        }

        fprintf(manifest, "%s\tsha256:%s\t%llu\n", relative, digest, (unsigned long long) size);
    }

    char end[8];
    read_exact(end, sizeof(end), "TRUNCATED");
    if (memcmp(end, FRAME_END, sizeof(end)) != 0) {
        fail("MALFORMED");
    }

    if (fflush(manifest) != 0 || fclose(manifest) != 0) {
        fail("STAGING");
    }
    if (chmod(MANIFEST_PATH, 0444) != 0) {
        fail("STAGING");
    }
    if (chmod(FILES_DIR, 0555) != 0 || chmod(SOURCE_ROOT, 0555) != 0) {
        fail("STAGING");
    }

    /*
     * FREEZE.
     *
     * The one privileged operation in this program, and every argument to it is a compile-time constant. It
     * happens after the last write and before any privilege is dropped, because afterwards there is no
     * capability left to perform it -- which is the point.
     */
    if (mount("none", SOURCE_ROOT, NULL,
              MS_REMOUNT | MS_RDONLY | MS_NOEXEC | MS_NOSUID | MS_NODEV, NULL) != 0) {
        fail("FREEZE");
    }

    drop_all_privilege();

    emit("bootstrap_outcome", "PASSED");

    /*
     * Hand over to the verifier.
     *
     * A fixed interpreter, a fixed script and a fixed mode word: three compile-time constants and no value
     * that came from the stream. The verifier runs unprivileged, on the frozen filesystem, and is what
     * actually reports what the sandbox can see.
     */
    /* The mode word, matched against a closed list rather than passed through. argv reaches this program
     * from the probe enumeration, which is server-side and fixed; comparing it here means the string handed
     * to the verifier is one of two compile-time literals whatever argv actually contained. */
    const char *mode = VERIFIER_MODE;
    if (argc > 1 && argv[1] != NULL && strcmp(argv[1], BOUNDARY_MODE) == 0) {
        mode = BOUNDARY_MODE;
    }
    char *const handover[] = {(char *) SHELL, (char *) VERIFIER, (char *) mode, NULL};
    char *const envp[] = {NULL};
    execve(SHELL, handover, envp);
    fail("HANDOFF");
    return 0;
}
