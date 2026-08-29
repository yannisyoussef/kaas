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

case "$mode" in
inspect)
    emit_tooling id stat ls awk env find

    emit uid "$(id -u)"
    emit gid "$(id -g)"
    emit groups "$(id -G | tr ' ' ',')"

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
*)
    emit error unsupported_mode
    exit 64
    ;;
esac
