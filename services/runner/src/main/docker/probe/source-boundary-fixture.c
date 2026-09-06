/*
 * TEST-ONLY. Plants the fixtures that make the source filesystem's behaviour distinguishable from the file's.
 *
 * WHY THIS PROGRAM EXISTS SEPARATELY
 *
 * KAAS-18 ended unable to say whether execution was refused by the filesystem or by the absence of an
 * executable bit, because production source is always 0444 and the two explanations produce the same result.
 * Telling them apart needs a file that IS executable on the filesystem under test, and the production bundle
 * format cannot express a mode -- deliberately, and that is not being changed to make a test easier.
 *
 * So the fixtures are planted by this, which is a different binary, selected by a different server-side
 * workload, and named by nothing on the delivery path. The production bootstrap is untouched: this runs
 * first, plants two files, and hands over to it unmodified.
 *
 * WHAT IT MAY DO
 *
 * Create a permissive control filesystem, write two fixtures onto it and onto the source filesystem, and
 * exec the real bootstrap. It reads no input, parses nothing, and takes no argument of its own. It cannot
 * appear in a production launch: DockerSandboxLauncher names /source-bootstrap and only /source-bootstrap,
 * and an architecture test asserts that.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <unistd.h>

#define SOURCE_FILES  "/kaas/source/files"
/* Under /tmp, because the container's root filesystem is read-only and /kaas exists only as the mount point
 * for the source filesystem. /tmp is a writable tmpfs the profile already provides -- and it is mounted
 * noexec, which is why a fresh tmpfs is mounted OVER this directory rather than the fixtures being written
 * into /tmp directly. The control has to permit execution or it is not a control. */
#define CONTROL_ROOT  "/tmp/kaas-control"
#define BOOTSTRAP     "/source-bootstrap"
#define SETUID_SEED   "/fixture-setuid"

static const char SCRIPT[] = "#!/bin/sh\necho FIXTURE_RAN\n";

static void note(const char *key, const char *value) {
    fprintf(stdout, "%s=%s\n", key, value);
    fflush(stdout);
}

/* Writes one fixture pair into a directory: an executable script and a setuid-root copy of a real binary. */
static void plant(const char *directory) {
    char path[256];

    snprintf(path, sizeof(path), "%s/fixture-exec.sh", directory);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0555);
    if (fd < 0) {
        note("fixture_plant", "EXEC_FAILED");
        return;
    }
    if (write(fd, SCRIPT, sizeof(SCRIPT) - 1) != (ssize_t) (sizeof(SCRIPT) - 1)) {
        note("fixture_plant", "EXEC_SHORT_WRITE");
    }
    close(fd);
    /* Set again after closing, because a creation mode is filtered through the umask. A fixture that is not
     * actually executable would make every refusal below meaningless. */
    chmod(path, 0555);

    snprintf(path, sizeof(path), "%s/fixture-suid", directory);
    int from = open(SETUID_SEED, O_RDONLY);
    int to = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (from >= 0 && to >= 0) {
        char buffer[65536];
        ssize_t got;
        while ((got = read(from, buffer, sizeof(buffer))) > 0) {
            ssize_t put = write(to, buffer, (size_t) got);
            (void) put;
        }
    }
    if (from >= 0) {
        close(from);
    }
    if (to >= 0) {
        close(to);
        /* Owned by root already, because this runs as root during construction. The bit is what matters. */
        chmod(path, 04755);
    }
}

int main(int argc, char **argv) {
    /* A permissive control filesystem, created here rather than by the launcher.
     *
     * The launcher builds production sandboxes and has no business knowing about a control mount. Creating it
     * from inside costs the construction phase nothing it does not already hold, and keeps the test-only
     * concern entirely inside the test-only binary. */
    if (mkdir(CONTROL_ROOT, 0755) != 0 && errno != EEXIST) {
        note("fixture_control", "MKDIR_FAILED");
    }
    if (mount("none", CONTROL_ROOT, "tmpfs", 0, "size=8m") != 0) {
        note("fixture_control", "MOUNT_FAILED");
    }

    if (mkdir("/kaas/source", 0755) != 0 && errno != EEXIST) {
        note("fixture_plant", "SOURCE_ROOT_FAILED");
    }
    if (mkdir(SOURCE_FILES, 0755) != 0 && errno != EEXIST) {
        note("fixture_plant", "FILES_FAILED");
    }

    /* Both filesystems get identical fixtures. One variable separates them: the mount flags. */
    plant(SOURCE_FILES);
    plant(CONTROL_ROOT);
    note("fixture_plant", "DONE");

    /* Hand over to the real bootstrap, unmodified, with the arguments it would otherwise have received. It
     * freezes the source filesystem -- with the fixtures on it -- and drops every capability. */
    char *forwarded[8];
    int count = 0;
    forwarded[count++] = (char *) BOOTSTRAP;
    for (int i = 1; i < argc && count < 7; i++) {
        forwarded[count++] = argv[i];
    }
    forwarded[count] = NULL;
    execv(BOOTSTRAP, forwarded);
    note("fixture_plant", "HANDOFF_FAILED");
    return 0;
}
