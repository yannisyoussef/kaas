#!/bin/sh
# Trusted synthetic security probe. Repository-controlled, baked into an allowlisted image, and the
# only executable content this sandbox will ever run in this slice.
#
# It reports what it can observe about its own confinement as key=value lines on stdout. It never
# attempts to exploit the host: every check is an observation or a bounded, self-limiting attempt
# whose *failure* is the evidence. A probe that could damage a host would be a worse thing to run
# than the untrusted content it exists to make safe.
#
# Evidence is ENUMERATED, not guessed at. An earlier version asked whether a handful of named paths
# existed -- /host, /workspace, /var/run/docker.sock, /dev/kvm -- and reported "absent" for each.
# That answers a question nobody hostile would ask: a bind mount at /secrets, a socket at
# /run/docker.sock, or a block device renamed to /dev/loop0 all reported clean while the sandbox
# held the host. Every check below now reports the whole set it observes and lets the gate compare
# that against what the profile permits, so a surface nobody thought to name is still visible.
set -u

mode="${1:-inspect}"

emit() { printf '%s=%s\n' "$1" "$2"; }

# Reports whether an applet this probe's evidence depends on actually exists. A missing binary and a
# denied operation are indistinguishable at the exit code, so without this a base-image change could
# silently turn every check that shells out into an unconditional pass.
emit_tooling() {
    missing=""
    for tool in "$@"; do
        command -v "$tool" >/dev/null 2>&1 || missing="${missing}${tool},"
    done
    if [ -z "$missing" ]; then
        emit probe_tooling present
    else
        emit probe_tooling "missing:$missing"
    fi
}

# ---------------------------------------------------------------------------------------------------
# Proxied-request helpers.
#
# Defined once, at the top, because two modes need them: the `egress` security probes and the synthetic
# workload's own egress mode. Two copies of a request writer would be two chances to get the framing wrong,
# and the copy that is wrong is the one that emits a bare LF -- which the proxy refuses on purpose, because a
# bare LF is a header boundary to one reader and not to another, and that difference is where request
# smuggling lives. Every terminator below is written explicitly as CRLF for that reason; a shell here-string
# or a multi-line quoted string emits bare LF, and the first version of this probe did exactly that and got a
# 400 for every request, which was the parser working as intended.
#
# The destinations are never arguments a caller chose. They arrive in environment variables the trusted
# launcher sets from the execution's own policy.
# ---------------------------------------------------------------------------------------------------

# $1 method, $2 request target, $3 Host header value. Prints the raw response.
proxy_send() {
    printf '%s %s HTTP/1.1\r\nHost: %s\r\nProxy-Authorization: Bearer %s\r\nConnection: close\r\n\r\n' \
        "$1" "$2" "$3" "${KAAS_EGRESS_CAPABILITY:-}" \
        | nc -w 15 "${KAAS_EGRESS_PROXY_HOST:-}" "${KAAS_EGRESS_PROXY_PORT:-}" 2>/dev/null
}

# $1 host, $2 port. Opens a CONNECT tunnel and prints whatever the proxy answered.
#
# The tunnel is not used for anything: a workload here has no TLS, and intercepting the tenant's TLS to
# inspect it is exactly what this design refuses to do. What the CONNECT response proves is the whole of the
# proxy's decision -- authorized, resolved, classified, connected -- with the payload left end to end.
#
# The short idle timeout is the exit condition. On success the proxy answers and then waits for bytes that
# never come, so nothing here would ever close the connection on its own.
proxy_connect() {
    printf 'CONNECT %s:%s HTTP/1.1\r\nHost: %s:%s\r\nProxy-Authorization: Bearer %s\r\n\r\n' \
        "$1" "$2" "$1" "$2" "${KAAS_EGRESS_CAPABILITY:-}" \
        | nc -w 5 "${KAAS_EGRESS_PROXY_HOST:-}" "${KAAS_EGRESS_PROXY_PORT:-}" 2>/dev/null
}

# The status line's code. Prints nothing when nothing came back at all, which callers report as "none" -- a
# different fact from a refusal, and one that must not be reported as one.
status_of() {
    printf '%s' "$1" | head -n 1 | awk '{print $2}' | tr -d '\r'
}

# The proxy's own reason header. Present on every refusal the proxy issues and on nothing else, so its
# ABSENCE alongside a status line is the evidence that the proxy authorized and connected and the status
# came from the target.
denial_of() {
    printf '%s' "$1" | grep -i '^X-KaaS-Egress-Denial:' | head -n 1 | cut -d: -f2- | tr -d ' \r'
}

emit_status() {
    code="$(status_of "$2")"
    emit "$1" "${code:-none}"
}

emit_denial() {
    reason="$(denial_of "$2")"
    emit "$1" "${reason:-none}"
}

case "$mode" in
inspect)
    emit_tooling id stat ls awk env find

    emit uid "$(id -u)"
    emit gid "$(id -g)"
    emit groups "$(id -G | tr ' ' ',')"

    # WHAT KERNEL IS ACTUALLY SERVING THIS SANDBOX'S SYSCALLS.
    #
    # Reported from inside, which is the point. A launcher asking the daemon which runtime it assigned is the
    # daemon answering a question about itself; this is the workload observing what is underneath it. gVisor
    # serves a fixed synthetic kernel version that no ordinary host reports, and a container cannot choose
    # what /proc/version says about it — so "requested runsc" and "actually running under runsc" are
    # separately observable rather than the same claim twice.
    emit runtime_kernel_release "$(uname -r 2>/dev/null || echo unknown)"

    # Root filesystem must be read-only; the approved tmpfs must not be.
    #
    # The target is a directory this user owns. Writing to "/" would be refused by ordinary permissions even on
    # a writable filesystem, so it would prove nothing about the mount -- the check would pass for the wrong
    # reason and keep passing if read-only were switched off.
    #
    # That ownership is the whole basis of the check, and it lives in one Dockerfile line. So the probe reports
    # who actually owns the directory: if the RUN that chowns it is ever dropped, this stops matching the uid
    # and the gate refuses the evidence rather than reading an ordinary permission denial as a read-only mount.
    emit probe_owned_owner "$(stat -c %u /probe-owned 2>/dev/null || echo missing)"
    if echo probe 2>/dev/null > /probe-owned/write-test; then
        emit rootfs_writable true
        rm -f /probe-owned/write-test 2>/dev/null
    else
        emit rootfs_writable false
    fi
    if echo probe 2>/dev/null > /tmp/probe-write; then
        emit tmp_writable true
        rm -f /tmp/probe-write 2>/dev/null
    else
        emit tmp_writable false
    fi

    # The whole mount table, not a guess at which paths matter. This single observation subsumes the
    # host-mount, docker-socket and tmpfs questions: a bind mount cannot hide under a name the probe
    # was not told to look for, because the gate compares the entire set against what it expects.
    emit mount_points "$(awk '{print $2}' /proc/self/mounts 2>/dev/null | sort | tr '\n' ',')"
    emit mount_writable "$(awk '$4 ~ /(^|,)rw(,|$)/ {print $2}' /proc/self/mounts 2>/dev/null | sort | tr '\n' ',')"
    # Sockets anywhere on the sandbox's own filesystems. A daemon socket is only dangerous if it is
    # reachable, and this finds it wherever it was mounted.
    emit unix_sockets "$(find / -xdev -type s 2>/dev/null | sort | tr '\n' ',')"

    # Capability sets, read from the kernel rather than asserted by configuration. All five are
    # reported: a bounding set of zero does not by itself imply a permitted set of zero, because
    # post-exec permitted includes P(inheritable) & F(inheritable), which the bounding set does not mask.
    if [ -r /proc/self/status ]; then
        emit cap_eff "$(awk '/^CapEff:/ {print $2}' /proc/self/status)"
        emit cap_prm "$(awk '/^CapPrm:/ {print $2}' /proc/self/status)"
        emit cap_bnd "$(awk '/^CapBnd:/ {print $2}' /proc/self/status)"
        emit cap_inh "$(awk '/^CapInh:/ {print $2}' /proc/self/status)"
        emit cap_amb "$(awk '/^CapAmb:/ {print $2}' /proc/self/status)"
        emit no_new_privs "$(awk '/^NoNewPrivs:/ {print $2}' /proc/self/status)"
        emit seccomp "$(awk '/^Seccomp:/ {print $2}' /proc/self/status)"
    else
        emit cap_eff unknown
    fi

    # Whether this container's uid is mapped to a different host uid. Without a user namespace the
    # identity mapping means container uid 65534 IS host uid 65534, and the deployment should know
    # that rather than read "non-root" as though it were host isolation.
    emit uid_map "$(awk '{print $1 ":" $2 ":" $3}' /proc/self/uid_map 2>/dev/null | tr '\n' ',')"

    # Device surface, reported BY TYPE rather than by filename prefix. Counting names starting with
    # sd/nvme/vd was defeated by attaching the host's root partition as /dev/loop0: the count stayed
    # at zero while the sandbox read the raw disk.
    emit dev_entries "$(ls /dev 2>/dev/null | sort | tr '\n' ',' )"
    emit block_device_nodes "$(find /dev -type b 2>/dev/null | sort | tr '\n' ',')"
    emit char_device_nodes "$(find /dev -type c 2>/dev/null | sort | tr '\n' ',')"

    # EVERY setuid and setgid binary reachable in the sandbox.
    #
    # This is the escalation path no-new-privileges exists to close, observed directly rather than inferred
    # from a flag. It reports the same thing under every runtime, which matters because the flag does not:
    # gVisor's /proc/self/status carries no NoNewPrivs line at all, so on that runtime the flag-based check
    # can say nothing and this is the control that still can.
    #
    # An empty set means there is nothing in the sandbox for a privilege transition to act on, whether or not
    # the kernel is willing to perform one.
    emit setuid_binaries "$(find / -xdev \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null | sort | tr '\n' ',')"

    # Kernel-internal paths, reported as WHICH OF THEM EXIST rather than assumed to be overmounted.
    #
    # runc masks these by mounting over them, so they appear in the mount table. gVisor never implemented
    # them, so they are absent entirely. Absent is at least as strong as masked, but the gate cannot tell
    # absent from "the probe did not look" unless the probe says what it looked at -- so it emits both the
    # list it examined and the subset that exists. A path that is present and NOT overmounted is the case this
    # check exists to catch (a daemon started with systempaths=unconfined), and it is still a failure.
    emit kernel_paths_checked "/proc/kcore,/proc/keys,/proc/timer_list,/proc/scsi,/sys/firmware,"
    kernel_paths_present=""
    for kernel_path in /proc/kcore /proc/keys /proc/timer_list /proc/scsi /sys/firmware; do
        if [ -e "$kernel_path" ]; then
            kernel_paths_present="$kernel_paths_present$kernel_path,"
        fi
    done
    emit kernel_paths_present "$kernel_paths_present"

    # For a path that exists and is NOT overmounted, whether it actually yields anything.
    #
    # gVisor presents a synthetic /sys/firmware that no runtime overmounts, because there is nothing behind it
    # to hide. An empty directory is not an exposed kernel internal, and failing it would be failing a sandbox
    # for being stricter. A NON-empty one is the systempaths=unconfined case and must still fail, so the
    # distinction is emptiness rather than existence.
    kernel_paths_nonempty=""
    for kernel_path in /proc/kcore /proc/keys /proc/timer_list /proc/scsi /sys/firmware; do
        if [ -d "$kernel_path" ]; then
            [ -n "$(ls -A "$kernel_path" 2>/dev/null)" ] && kernel_paths_nonempty="$kernel_paths_nonempty$kernel_path,"
        elif [ -f "$kernel_path" ]; then
            [ -s "$kernel_path" ] && kernel_paths_nonempty="$kernel_paths_nonempty$kernel_path,"
        fi
    done
    emit kernel_paths_nonempty "$kernel_paths_nonempty"

    # The environment must be an explicit allowlist, not an inheritance. Names only, never values:
    # a leaked secret must not be copied into the evidence by the check that detects it.
    emit env_names "$(env | cut -d= -f1 | sort | tr '\n' ',')"
    emit env_count "$(env | wc -l | tr -d ' ')"
    ;;
network)
    emit_tooling nc nslookup ip

    # Positive evidence FIRST, because reachability alone cannot tell "no network" from "a network
    # with nothing routable on it". On an egress-filtered host a fully attached container reports
    # every destination unreachable, exactly as an isolated one does -- and a sandbox with a routable
    # address can still reach its neighbours. These three observations distinguish the two states
    # without depending on anything outside the sandbox.
    emit net_global_addresses "$(ip -o addr show scope global 2>/dev/null | wc -l | tr -d ' ')"
    emit net_default_routes "$(ip route show default 2>/dev/null | wc -l | tr -d ' ')"
    emit net_interfaces_up "$(ip -o link show up 2>/dev/null | grep -vc ': lo:')"

    # Reachability attempts remain, as corroboration rather than as the proof. Each is bounded so a
    # hung network cannot hold the sandbox open past its own deadline, and none sends anything
    # meaningful. Loopback is deliberately absent: --network none leaves a fully working lo, so a
    # loopback attempt can only ever report "nothing is listening", which is not evidence of anything.
    gateway="$(ip route show default 2>/dev/null | awk '/via/ {print $3; exit}')"
    for target in "1.1.1.1 53 public" "10.0.0.1 80 private" \
                  "169.254.169.254 80 metadata" "169.254.0.1 80 link_local" \
                  "fd00:ec2::254 80 metadata_v6" "host.docker.internal 80 docker_host"; do
        set -- $target
        if nc -w 2 -z "$1" "$2" 2>/dev/null; then
            emit "net_$3" reachable
        else
            emit "net_$3" unreachable
        fi
    done
    # The real gateway, discovered rather than hardcoded. A hardcoded 172.17.0.1 proved only that the
    # daemon was not listening on 2375, which is true from a fully bridged container too.
    if [ -n "$gateway" ] && nc -w 2 -z "$gateway" 2375 2>/dev/null; then
        emit net_gateway reachable
    else
        emit net_gateway unreachable
    fi
    # DNS is a separate exfiltration channel from TCP reachability.
    if nslookup example.com 2>/dev/null >/dev/null; then
        emit net_dns resolvable
    else
        emit net_dns unresolvable
    fi
    ;;
processes)
    emit_tooling sleep
    # Bounded, deliberately finite, and self-terminating: this establishes that a PID ceiling
    # exists without ever becoming the fork bomb it is testing for.
    limit="${2:-200}"
    # Asks for more processes than the ceiling permits, so the ceiling is what stops it rather than the loop
    # running out. Every child exits on its own, so this is finite and self-limiting: it establishes that a
    # bound exists without ever becoming the fork bomb it is testing for.
    #
    # The count is emitted as it goes, not summarised at the end, because busybox's shell *exits* when it
    # cannot fork rather than returning an error a script could handle. Nothing after the loop would run. So
    # the last value printed is how far it got, and the absence of the completion marker is the evidence that
    # something stopped it. printf is a builtin, so reporting still works with no forks left.
    emit processes_requested "$limit"
    i=0
    while [ "$i" -lt "$limit" ]; do
        sleep 5 &
        i=$((i + 1))
        emit processes_started "$i"
    done
    emit processes_loop_completed true
    ;;
memory)
    emit_tooling head tr
    # Allocates in bounded steps until it is stopped. The kernel terminating this process is the
    # expected outcome; reaching the end of the loop is the failure.
    megabytes="${2:-256}"
    # Emitted BEFORE the loop, because under the real profile the kernel OOM-kills this process
    # mid-allocation and nothing after the loop ever runs. Without this the sandbox produces no
    # observations at all, and a gate that reads an empty result as success would pass a run that
    # never happened. Its presence is what distinguishes "the ceiling stopped it" from "nothing ran".
    emit memory_requested_mb "$megabytes"
    i=0
    while [ "$i" -lt "$megabytes" ]; do
        # Each iteration holds roughly one megabyte of resident shell variable.
        eval "chunk$i=\$(head -c 1048576 /dev/zero | tr '\\0' 'x')" 2>/dev/null || break
        i=$((i + 1))
    done
    emit memory_allocated_mb "$i"
    emit memory_loop_completed true
    ;;
sleep)
    emit_tooling sleep
    # Sleeps past any sane deadline. The launcher must not need this process to cooperate.
    emit sleeping true
    sleep "${2:-3600}"
    emit sleep_completed true
    ;;
sourceverify|sourceboundary)
    # THE PLATFORM'S SOURCE VERIFIER, running on the frozen filesystem.
    #
    # It reads tenant bytes and hashes them. It does not interpret them. There is no parser, no syntax check,
    # no include resolution and no evaluation of any kind -- not because those would be hard, but because this
    # slice delivers source as DATA and an engine is a different decision with a different adjudication.
    #
    # It runs AFTER the bootstrap has populated the source filesystem, remounted it read-only, and dropped
    # every capability. So everything below is observed by the process a future engine would be, which is the
    # only process whose security context is worth measuring.
    emit_tooling sha256sum awk
    root=/kaas/source
    manifest="$root/manifest.tsv"

    # THE BOUNDARY COMPARISONS, when this is the boundary measurement rather than an ordinary verification.
    #
    # One branch serves both because everything below the guard is identical: the same frozen filesystem, the
    # same digests, the same observations. What the boundary measurement adds is a second filesystem to
    # compare against, and fixtures the production format cannot express -- which is why it is a separate
    # server-side workload and why an execution carrying tenant source never selects it.
    if [ "$mode" = sourceboundary ]; then
        # THE BOUNDARY MEASUREMENT. Everything sourceverify reports, plus the two comparisons that make the
        # filesystem's behaviour distinguishable from the file's.
        #
        # A fixture planter ran before the bootstrap and left identical files on two filesystems: the frozen
        # source filesystem, and a permissive tmpfs it created for the purpose. They differ in their mount flags
        # and in nothing else. Each fixture is genuinely executable and genuinely setuid-root, which the
        # production bundle format cannot express and the production materialiser would never write -- that is the
        # entire reason this is a separate workload.
        #
        # Without the control half, "the hardened filesystem refused it" would be equally consistent with the
        # filesystem enforcing noexec and with the file simply not being runnable. KAAS-18 ended on exactly that
        # ambiguity.
        control=/tmp/kaas-control

        if [ -x "$control/fixture-exec.sh" ] && "$control/fixture-exec.sh" >/dev/null 2>&1; then
            emit exec_control EXECUTED
        else
            emit exec_control REFUSED
        fi
        if [ -f /kaas/source/files/fixture-exec.sh ] && /kaas/source/files/fixture-exec.sh >/dev/null 2>&1; then
            emit exec_hardened EXECUTED
        else
            emit exec_hardened REFUSED
        fi

        # The setuid pair. Reported as three distinct states rather than a boolean, because "it did not escalate"
        # and "it would not run at all" are different facts and only one of them is about nosuid.
        out=$("$control/fixture-suid" 2>/dev/null || echo refused)
        case "$out" in
            *euid=0*) emit suid_control ESCALATED ;;
            *refused*) emit suid_control REFUSED ;;
            *) emit suid_control NOT_ESCALATED ;;
        esac
        out=$(/kaas/source/files/fixture-suid 2>/dev/null || echo refused)
        case "$out" in
            *euid=0*) emit suid_hardened ESCALATED ;;
            *refused*) emit suid_hardened REFUSED ;;
            *) emit suid_hardened NOT_ESCALATED ;;
        esac

        # A write to the control, so read-only on the source is a difference between two filesystems rather than
        # a statement about one.
        if echo probe 2>/dev/null > "$control/kaas-write-probe"; then
            emit write_control ACCEPTED
            rm -f "$control/kaas-write-probe" 2>/dev/null
        else
            emit write_control REFUSED
        fi
    fi

    if [ ! -f "$manifest" ]; then
        emit source_manifest missing
        emit workload_outcome FAILED
        exit 0
    fi

    # WHAT THE CONSUMER IS. Reported before anything about the filesystem, because a mount flag observed by a
    # privileged process says nothing about what an unprivileged one can do.
    emit source_consumer_uid "$(id -u 2>/dev/null || echo unknown)"
    emit source_consumer_gid "$(id -g 2>/dev/null || echo unknown)"
    # Every capability set, not just the effective one. A process with an empty effective set and a populated
    # bounding set has not dropped its privilege; it has put it down within reach.
    caps=$(awk '/^Cap(Inh|Prm|Eff|Bnd|Amb):/ {gsub(/^0+/, "", $2); if ($2 != "") nonzero = 1} END {print nonzero ? "PRESENT" : "EMPTY"}' /proc/self/status 2>/dev/null)
    emit final_consumer_capabilities "${caps:-UNKNOWN}"
    emit source_no_new_privileges "$(awk '/^NoNewPrivs:/ {print ($2 == 1) ? "true" : "false"}' /proc/self/status 2>/dev/null || echo unknown)"

    # THE MOUNT, READ FROM BOTH OPTION NAMESPACES.
    #
    # mountinfo splits what it knows in two: the per-mount flags in field 6, and the superblock options after
    # the separator. A parser that reads only one of them can call a filesystem hardened when it is not, so
    # both are captured and the flags are matched against the union.
    mountline=$(awk '$5 == "/kaas/source" {
        flags = $6
        for (i = 7; i <= NF; i++) if ($i == "-") { super = $(i + 3); break }
        print flags "," super
        exit
    }' /proc/self/mountinfo 2>/dev/null || true)
    emit source_mount_options "${mountline:-absent}"
    emit source_filesystem "$(awk '$5 == "/kaas/source" { for (i = 6; i <= NF; i++) if ($i == "-") { print $(i + 1); exit } }' /proc/self/mountinfo 2>/dev/null || echo unknown)"
    emit source_mount_ro "$(case ",${mountline}," in *,ro,*) echo true;; *) echo false;; esac)"
    emit source_mount_noexec "$(case ",${mountline}," in *,noexec,*) echo true;; *) echo false;; esac)"
    emit source_mount_nosuid "$(case ",${mountline}," in *,nosuid,*) echo true;; *) echo false;; esac)"
    emit source_mount_nodev "$(case ",${mountline}," in *,nodev,*) echo true;; *) echo false;; esac)"

    # NO INGRESS. There must be exactly one tenant source filesystem reachable from here, and it must be the
    # hardened one. A weaker second copy would make everything above cosmetic, because hostile code does not
    # use the path it was meant to.
    emit source_mounts_visible "$(awk '$2 ~ /^\/kaas\/source/' /proc/self/mounts 2>/dev/null | wc -l | tr -d ' ')"
    emit source_ingress_visible "$(awk '$3 == "9p"' /proc/self/mounts 2>/dev/null | wc -l | tr -d ' ')"

    # WRITE REFUSAL, OBSERVED. A mount that reports ro and accepts a write is a mount that reports.
    if echo probe 2>/dev/null > "$root/kaas-write-probe"; then
        emit source_write_refused false
        rm -f "$root/kaas-write-probe" 2>/dev/null
    else
        emit source_write_refused true
    fi
    # And the filesystem cannot be reopened. The bootstrap dropped what it would take; this is that drop
    # observed from the outside rather than trusted.
    if mount -o remount,rw "$root" >/dev/null 2>&1; then
        emit source_remount_refused false
    else
        emit source_remount_refused true
    fi

    # No setuid or setgid anywhere under the source root, and nothing but regular files and directories.
    emit source_setuid_files "$(find "$root" \( -perm -4000 -o -perm -2000 \) -type f 2>/dev/null | wc -l | tr -d ' ')"
    emit source_irregular_entries "$(find "$root" ! -type f ! -type d 2>/dev/null | wc -l | tr -d ' ')"

    # EXECUTION, ATTEMPTED. The mount flags above are what the runtime SAYS; this is what it DOES.
    #
    # Reported separately from source_mount_noexec on purpose. Production files carry no executable bit, so a
    # refusal here alone would not distinguish "the filesystem refused it" from "the file was not executable"
    # -- which is exactly the confusion KAAS-18 ended on. The distinguishing evidence is the boundary suite's
    # control fixture, which is genuinely executable and runs on a permissive filesystem.
    execprobe=$(find "$root/files" -type f 2>/dev/null | head -n 1)
    if [ -n "$execprobe" ] && "$execprobe" >/dev/null 2>&1; then
        emit source_exec_refused false
    else
        emit source_exec_refused true
    fi

    format=$(awk 'NR==1 {print $1}' "$manifest")
    expected_digest=$(awk 'NR==1 {print $2}' "$manifest")
    expected_count=$(awk 'NR==1 {print $3}' "$manifest")
    emit source_format "$format"
    emit source_entry_count "$expected_count"
    emit source_bundle_digest "$expected_digest"

    # Every entry the manifest names, hashed from the FINAL mounted file. A path in the manifest that is not
    # there, or whose bytes differ, fails -- and so does a file present under the root that the manifest never
    # named.
    mismatches=0
    checked=0
    while IFS="$(printf '\t')" read -r path digest size; do
        [ -z "$path" ] && continue
        file="$root/files/$path"
        if [ ! -f "$file" ]; then
            mismatches=$((mismatches + 1))
            continue
        fi
        actual="sha256:$(sha256sum "$file" 2>/dev/null | awk '{print $1}')"
        actual_size=$(wc -c < "$file" 2>/dev/null | tr -d ' ')
        if [ "$actual" != "$digest" ] || [ "$actual_size" != "$size" ]; then
            mismatches=$((mismatches + 1))
        fi
        checked=$((checked + 1))
    done <<MANIFEST
$(tail -n +2 "$manifest")
MANIFEST

    # Files present under the root, excluding anything the boundary measurement planted.
    #
    # The exclusion is by an exact platform-chosen prefix and applies to a filename no bundle can produce: a
    # logical path comes from a sealed FeatureRevision and reaching this name would require the control plane
    # to have authorized it. In sourceverify -- the mode every execution carrying tenant source uses -- there
    # are no such files, so the two counts are the same number and this changes nothing about what a real
    # delivery has to satisfy.
    present=$(find "$root/files" -type f 2>/dev/null | grep -cv '/fixture-' || echo 0)
    emit source_entries_verified "$checked"
    emit source_entries_present "$present"
    emit source_entry_mismatches "$mismatches"

    # ONE BIT LEAVES THIS SANDBOX. Every observation above is diagnostic; the authoritative outcome is a
    # platform-defined PASS or FAIL, and no tenant filename or source byte appears in either.
    #
    # The boundary is part of the outcome rather than beside it: bytes that verify on a filesystem that is not
    # read-only, or is executable, or that the consumer could reopen, are not a passing execution.
    if [ "$mismatches" -eq 0 ] \
        && [ "$checked" = "$expected_count" ] \
        && [ "$present" = "$expected_count" ] \
        && [ "$format" = "kaas.source-bundle.v1" ] \
        && [ "$(case ",${mountline}," in *,ro,*) echo y;; esac)" = "y" ] \
        && [ "$(case ",${mountline}," in *,noexec,*) echo y;; esac)" = "y" ] \
        && [ "${caps:-UNKNOWN}" = "EMPTY" ]; then
        emit workload_identity KAAS_SYNTHETIC_V1
        emit workload_outcome PASSED
    else
        emit workload_identity KAAS_SYNTHETIC_V1
        emit workload_outcome FAILED
    fi
    ;;
hostileoutput)
    # OUTPUT SHAPED LIKE AN ATTACK ON THE READER, not on the sandbox.
    #
    # Everything here is legal for a workload to print and none of it threatens the boundary. What it
    # threatens is whoever renders it: an escape sequence can rewrite a terminal, a right-to-left override can
    # reorder how a whole evidence line reads without changing its bytes, and a value that looks like a path
    # is only inert for as long as nothing treats an observation as a filename.
    emit_tooling
    printf 'hostile_escape=\033[31mred\033[0m\n'
    printf 'hostile_bell=ding\007\n'
    printf 'hostile_rtlo=\342\200\256reversed\n'
    printf 'hostile_zwj=a\342\200\215b\n'
    printf 'hostile_traversal=../../etc/passwd\n'
    printf 'hostile_absolute=/etc/shadow\n'
    # A key carrying control characters, because sanitising only values would leave the map's own keys
    # attacker-shaped.
    printf 'hostile\033[1mkey=value\n'
    emit hostile_output_completed true
    ;;
output)
    # printf is a shell builtin, so this mode depends on no applet at all; the marker is emitted anyway so
    # that every mode's evidence is qualified the same way and the gate needs no per-mode exception.
    emit_tooling
    # Emits far more than the configured ceiling, to prove the collector bounds it rather than
    # buffering whatever an untrusted workload decides to produce.
    lines="${2:-200000}"
    emit output_requested_lines "$lines"
    i=0
    while [ "$i" -lt "$lines" ]; do
        printf 'output_flood=%s aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n' "$i"
        i=$((i + 1))
    done
    ;;
workload)
    # THE SYNTHETIC WORKLOAD. This is what "executing a run" means in this slice.
    #
    # It is not Karate and does not pretend to be. It runs no feature source, reads no secret, and
    # reaches no network — the point of it is that the LIFECYCLE around an execution can be proven
    # correct with a workload the platform wrote, before anything user-controlled is admitted.
    #
    # Its identity is fixed and reported, so a result carrying KAAS_SYNTHETIC_V1 can never be mistaken
    # for a real engine's output by anything downstream. Reporting an engine this did not run would be
    # the single most misleading thing this slice could do.
    expected="${2:-pass}"
    if [ "$expected" = "egress" ]; then
        emit_tooling awk tr sort nc nslookup
    else
        emit_tooling awk tr sort
    fi
    if [ "$expected" != "imposter" ]; then
        emit workload_identity KAAS_SYNTHETIC_V1
    else
        emit workload_identity SOMETHING_ELSE
    fi

    if [ "$expected" = "egress" ]; then
        # THE ALLOWLIST WORKLOAD.
        #
        # Still the platform's own workload, still no feature source and no secret — the only thing that
        # changes under an allowlist is that it has one reachable peer, and what it demonstrates is that the
        # peer is the ONLY one.
        #
        # Three scenarios, and the first two are only evidence together. "The destination the policy names is
        # reachable" is satisfied by a fully routed network; "nothing is reachable directly" is satisfied by a
        # workload with no network at all. Neither alone says anything about enforcement; the pair does.
        host="${KAAS_EGRESS_ALLOWED_HOST:-}"
        port="${KAAS_EGRESS_ALLOWED_PORT:-80}"
        scheme="${KAAS_EGRESS_ALLOWED_SCHEME:-HTTP}"
        denied_port="${KAAS_EGRESS_DENIED_PORT:-1}"
        passed=0
        failed=0

        # 1. THROUGH THE PROXY, to the destination the policy names.
        if [ "$scheme" = "HTTPS" ]; then
            allowed="$(proxy_connect "$host" "$port")"
        else
            allowed="$(proxy_send GET "http://${host}:${port}/" "${host}:${port}")"
        fi
        allowed_status="$(status_of "$allowed")"
        allowed_denial="$(denial_of "$allowed")"
        emit egress_allowed_status "${allowed_status:-none}"
        emit egress_allowed_denial "${allowed_denial:-none}"
        # PASSED when the proxy authorized and connected, which is what the PLATFORM is responsible for.
        # What the target then answers is the target's business: a 404 from an authorized destination is a
        # successful egress and an unsuccessful request, and conflating the two would turn this workload into
        # a health check for somebody else's service. The absence of the proxy's own denial header is what
        # distinguishes the two, because the proxy puts that header on every refusal it issues and on nothing
        # else -- so a 503 it produced cannot be mistaken for a 503 the target produced.
        if [ -n "$allowed_status" ] && [ -z "$allowed_denial" ]; then
            emit scenario_egress_allowed PASSED
            passed=$((passed + 1))
        else
            emit scenario_egress_allowed FAILED
            failed=$((failed + 1))
        fi

        # 2. A DESTINATION THE POLICY DOES NOT NAME. The same host on a port the launcher established is
        #    absent from the policy, so this is refused by construction rather than by hope, and the proxy
        #    refuses it before resolving anything -- no name has to exist for this to be evidence.
        if [ "$scheme" = "HTTPS" ]; then
            denied="$(proxy_connect "$host" "$denied_port")"
        else
            denied="$(proxy_send GET "http://${host}:${denied_port}/" "${host}:${denied_port}")"
        fi
        emit_status egress_denied_status "$denied"
        denied_reason="$(denial_of "$denied")"
        emit egress_denied_reason "${denied_reason:-none}"
        # A deliberate denial is SUCCESSFUL security evidence, not a failed test. There is no tenant test
        # here, and reporting a correct refusal as a failure would teach a reader exactly the wrong thing.
        if [ "$denied_reason" = "DESTINATION_NOT_ALLOWED" ]; then
            emit scenario_egress_denied PASSED
            passed=$((passed + 1))
        else
            emit scenario_egress_denied FAILED
            failed=$((failed + 1))
        fi

        # 3. NO SECOND ROUTE. A hostile workload ignores every proxy setting and opens a socket itself, so
        #    topology has to defeat it rather than configuration. Enumerated rather than asked about one named
        #    target: a surface nobody thought to name is exactly the one that would still be reachable.
        reachable=""
        for target in "1.1.1.1 53 public" "10.0.0.1 80 private" \
                      "169.254.169.254 80 metadata" "172.17.0.1 2375 daemon"; do
            set -- $target
            if nc -w 2 -z "$1" "$2" 2>/dev/null; then
                emit "egress_direct_$3" reachable
                reachable="${reachable}$3,"
            else
                emit "egress_direct_$3" unreachable
            fi
        done
        # Independent name resolution would let a workload discover addresses without the proxy's decision
        # being involved at all, which is the same escape by a quieter route.
        if nslookup "$host" 2>/dev/null >/dev/null; then
            emit egress_direct_dns resolvable
            reachable="${reachable}dns,"
        else
            emit egress_direct_dns unresolvable
        fi
        if [ -z "$reachable" ]; then
            emit scenario_egress_no_bypass PASSED
            passed=$((passed + 1))
        else
            emit scenario_egress_no_bypass FAILED
            failed=$((failed + 1))
        fi

        emit workload_passed "$passed"
        emit workload_failed "$failed"
        if [ "$failed" -gt 0 ]; then
            emit workload_outcome FAILED
        else
            emit workload_outcome PASSED
        fi
        # Exit zero either way, for the same reason every other workload mode does: a failing workload is not
        # a failing execution, and collapsing the two here would make the sandbox's exit code report the
        # outcome -- which is the conflation the orthogonal-outcome rule exists to prevent.
        exit 0
    fi

    # A fixed, deterministic set of assertions. Deterministic on purpose: the lifecycle is what is
    # under test, so a workload whose outcome varied would make every lifecycle failure ambiguous.
    #
    # The expected outcome is chosen by the caller so BOTH terminal outcomes are reachable in a test.
    # A workload that could only pass would leave the FAILED path — a real, distinct lifecycle
    # transition — never once exercised, and the first genuine test failure would be the first time
    # that code ever ran.
    emit workload_expectation "$expected"

    passed=0
    failed=0
    for scenario in arithmetic string ordering; do
        case "$scenario" in
        arithmetic) actual=$(awk 'BEGIN { print 2 + 2 }'); wanted=4 ;;
        string)     actual=$(printf 'kaas' | tr 'a-z' 'A-Z'); wanted=KAAS ;;
        ordering)   actual=$(printf 'b\na\nc\n' | sort | tr -d '\n'); wanted=abc ;;
        esac
        if [ "$actual" = "$wanted" ]; then
            emit "scenario_${scenario}" PASSED
            passed=$((passed + 1))
        else
            emit "scenario_${scenario}" FAILED
            failed=$((failed + 1))
        fi
    done

    # Two diagnostic shapes, so the runner's own evidence checks can be covered INDEPENDENTLY.
    #
    # Without them the identity check and the outcome check are a jointly-covered pair: delete either and the
    # other still refuses, so neither is actually proven. These produce exactly one defect each.
    if [ "$expected" = "silent" ]; then
        # Correct identity, no verdict. The shape of a workload killed after announcing itself.
        emit workload_note "no outcome will be reported"
        exit 0
    fi
    if [ "$expected" = "imposter" ]; then
        # A confident verdict under the wrong identity. The shape of something that is not our workload at all.
        emit workload_outcome PASSED
        exit 0
    fi

    # The deliberate failure, when one is asked for. A separate scenario rather than an inverted
    # assertion above, so a FAILED run still shows the genuine scenarios passing — which is what a
    # real partial failure looks like, and what the result document has to be able to represent.
    if [ "$expected" = "fail" ]; then
        emit scenario_expected_failure FAILED
        failed=$((failed + 1))
    fi

    emit workload_passed "$passed"
    emit workload_failed "$failed"
    if [ "$failed" -gt 0 ]; then
        emit workload_outcome FAILED
    else
        emit workload_outcome PASSED
    fi
    # Exit zero either way. A failing TEST is not a failing EXECUTION, and collapsing the two here
    # would make the sandbox's exit code report the test outcome — which is exactly the conflation
    # the orthogonal-outcome rule exists to prevent. The infrastructure succeeded: it ran the
    # workload and collected its result.
    exit 0
    ;;
egress)
    # Egress probes. Like every other mode here, the destinations are NOT arguments a caller chose: they
    # arrive in environment variables the trusted launcher sets from the execution's own policy, and the
    # sub-mode is one of a fixed set. Nothing a tenant writes reaches this script.
    #
    # The pair that matters most is "through the proxy it works" and "straight at the same address it does
    # not". Either alone proves nothing: a workload that cannot reach anything at all satisfies the second,
    # and a workload on a fully routed network satisfies the first.
    emit_tooling nc nslookup

    submode="${2:-status}"
    proxy_host="${KAAS_EGRESS_PROXY_HOST:-}"
    proxy_port="${KAAS_EGRESS_PROXY_PORT:-}"
    capability="${KAAS_EGRESS_CAPABILITY:-}"

    case "$submode" in
    allowed)
        # The success path: an ordinary proxied request to a destination the policy names.
        host="${KAAS_EGRESS_ALLOWED_HOST:-}"
        port="${KAAS_EGRESS_ALLOWED_PORT:-80}"
        response="$(proxy_send GET "http://${host}:${port}/ok" "${host}:${port}")"
        emit_status egress_allowed_status "$response"
        if printf '%s' "$response" | grep -q 'KAAS_EGRESS_TARGET_OK'; then
            emit egress_allowed_body present
        else
            emit egress_allowed_body absent
        fi
        ;;
    denied)
        # A destination the policy does not name. The refusal must come from the proxy, with a reason.
        host="${KAAS_EGRESS_DENIED_HOST:-}"
        response="$(proxy_send GET "http://${host}:80/ok" "${host}:80")"
        emit_status egress_denied_status "$response"
        emit_denial egress_denied_reason "$response"
        ;;
    private)
        # A destination the policy DOES name, whose DNS answer is a private address. The name passes policy
        # and the address does not, which is the only way to reach the classifier at all.
        host="${KAAS_EGRESS_PRIVATE_HOST:-}"
        response="$(proxy_send GET "http://${host}:80/ok" "${host}:80")"
        emit_status egress_private_status "$response"
        emit_denial egress_private_reason "$response"
        ;;
    redirect)
        # Escape by redirect. The proxy does not follow redirects; the client does, and that second request
        # is a new proxied request which must be authorized on its own. This probe performs both halves and
        # reports both, so "the redirect was returned" and "following it was refused" are separate evidence.
        host="${KAAS_EGRESS_ALLOWED_HOST:-}"
        port="${KAAS_EGRESS_ALLOWED_PORT:-80}"
        first="$(proxy_send GET "http://${host}:${port}/redirect" "${host}:${port}")"
        emit_status egress_redirect_first_status "$first"
        location="$(printf '%s' "$first" | grep -i '^Location:' | head -n 1 | cut -d: -f2- | tr -d ' \r')"
        if [ -z "$location" ]; then
            emit egress_redirect_location absent
        else
            emit egress_redirect_location present
            # Follow it exactly as a client would: a second proxied request to whatever it pointed at.
            target="$(printf '%s' "$location" | sed 's|^http://||' | cut -d/ -f1)"
            path="/$(printf '%s' "$location" | sed 's|^http://[^/]*/||')"
            second="$(proxy_send GET "http://${target}${path}" "${target}")"
            emit_status egress_redirect_second_status "$second"
            emit_denial egress_redirect_second_reason "$second"
        fi
        ;;
    bypass)
        # The load-bearing negative. A hostile workload ignores every proxy environment variable and opens a
        # socket straight at the address it wants. Topology has to defeat that, not configuration.
        #
        # Reported as an enumeration of what was reachable rather than as a verdict, so a surface nobody
        # thought to name is still visible in the evidence.
        for target in "${KAAS_EGRESS_DIRECT_IP:-11.0.0.9} ${KAAS_EGRESS_ALLOWED_PORT:-80} direct_target" \
                      "1.1.1.1 53 public" "10.0.0.1 80 private" \
                      "169.254.169.254 80 metadata" "172.17.0.1 2375 daemon"; do
            set -- $target
            if nc -w 2 -z "$1" "$2" 2>/dev/null; then
                emit "egress_direct_$3" reachable
            else
                emit "egress_direct_$3" unreachable
            fi
        done
        # Independent name resolution would let a workload discover addresses without the proxy's decision
        # being involved at all.
        if nslookup "${KAAS_EGRESS_ALLOWED_HOST:-example.com}" 2>/dev/null >/dev/null; then
            emit egress_direct_dns resolvable
        else
            emit egress_direct_dns unresolvable
        fi
        ;;
    tunnel)
        # Opens a CONNECT tunnel and holds it, so the test can fence the assignment underneath it and measure
        # how long the tunnel stays usable.
        #
        # The measurement is of nc's lifetime, not of a pipeline's. The first version held the connection open
        # with "{ printf; sleep N; } | nc" and timed the whole pipeline, which always reported N: the sleep
        # kept running after nc had exited, so a tunnel that was cut promptly looked identical to one that was
        # never cut at all. The revocation was working; the probe could not see it.
        #
        # A FIFO gives nc a stdin that never reaches end of file, so nc stays up until the far end closes,
        # and nc is the only background process -- which makes $! unambiguous.
        host="${KAAS_EGRESS_ALLOWED_HOST:-}"
        port="${KAAS_EGRESS_ALLOWED_PORT:-80}"
        hold="${KAAS_EGRESS_TUNNEL_SECONDS:-60}"
        rm -f /tmp/tunnel.in
        mkfifo /tmp/tunnel.in 2>/dev/null || { emit error tunnel_fifo_unavailable; exit 70; }

        start="$(date +%s)"
        nc -w "$hold" "$proxy_host" "$proxy_port" < /tmp/tunnel.in > /tmp/tunnel.out 2>/dev/null &
        tunnel_pid=$!
        # Held open for the whole probe, so nothing this end does can be mistaken for the far end closing.
        exec 3> /tmp/tunnel.in
        printf 'CONNECT %s:%s HTTP/1.1\r\nHost: %s:%s\r\nProxy-Authorization: Bearer %s\r\n\r\n' \
            "$host" "$port" "$host" "$port" "$capability" >&3

        # A byte a second into the tunnel. Not decoration: busybox nc does not exit merely because the far
        # end went away while its own stdin is still open, so polling liveness alone reports the full hold
        # whether the tunnel was cut or not — which is what the first two versions of this probe did. A write
        # onto a socket the proxy has closed fails, and that is what ends nc and stops the clock.
        #
        # Whatever crosses the tunnel is irrelevant to the far end, which reads nothing. The write is the
        # measurement, not the payload.
        elapsed=0
        while [ "$elapsed" -lt "$hold" ]; do
            kill -0 "$tunnel_pid" 2>/dev/null || break
            printf '.' >&3 2>/dev/null || break
            sleep 1
            elapsed=$((elapsed + 1))
        done
        finish="$(date +%s)"
        exec 3>&-
        kill "$tunnel_pid" 2>/dev/null

        emit_status egress_tunnel_open_status "$(cat /tmp/tunnel.out 2>/dev/null)"
        emit egress_tunnel_held_seconds "$((finish - start))"
        ;;
    *)
        emit error unsupported_egress_submode
        exit 64
        ;;
    esac
    ;;
*)
    emit error unsupported_mode
    exit 64
    ;;
esac
