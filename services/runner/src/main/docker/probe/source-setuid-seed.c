/*
 * TEST-ONLY. Prints the identity it is running under, and nothing else.
 *
 * The boundary measurement makes a copy of this setuid-root on two filesystems and runs both. If the runtime
 * performs setuid transitions, the copy on the permissive filesystem reports euid=0 and the copy on the
 * hardened one does not, and the difference is what nosuid means. If it reports the caller's own uid in both
 * places, then the runtime performs no such transition at all and the flag has no observable effect -- which
 * is a finding rather than a passing test, and is why this prints both ids instead of a verdict.
 *
 * KAAS-15 tried to make this claim with busybox `id`, which drops privileges of its own accord and would have
 * reported the same thing whether nosuid was enforced or not. This does nothing but read two numbers.
 */
#include <stdio.h>
#include <unistd.h>

int main(void) {
    printf("ruid=%d euid=%d\n", (int) getuid(), (int) geteuid());
    return 0;
}
