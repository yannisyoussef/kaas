/*
 * A platform-owned hostile workload, written in Java because the engine that would run tenant code is a JVM.
 *
 * WHY THIS EXISTS
 *
 * Every hostile probe in this repository so far has been a shell script. That was the right shape while the
 * sandbox ran shell, and it is the wrong shape for adjudicating whether a JVM containing an interpreter can
 * be contained: threads are not processes, a JVM's memory floor is not a shell's, and Java's networking does
 * not go through the tools a shell probe exercises.
 *
 * So this attempts, from inside the sandbox and under the exact posture a future engine would inherit, the
 * things tenant Karate could attempt through Java interop. It is NOT Karate and does not parse anything: it
 * is the platform asking what its own boundary does when the workload is a JVM.
 *
 * WHAT IT DOES NOT DO
 *
 * It does not attack the host. Every attempt below is bounded, self-limiting, and interesting only for
 * whether it is refused. Failure is the evidence.
 */
import java.io.File;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class HostileJvmProbe {

    /** Where the platform put tenant source. A constant here, exactly as it is everywhere else. */
    private static final String SOURCE_ROOT = "/kaas/source";

    private static void emit(String key, Object value) {
        System.out.println(key + "=" + value);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        emit("probe_identity", "KAAS_HOSTILE_JVM_V1");

        // THE PROCESS'S OWN SECURITY CONTEXT, read rather than assumed. A JVM reports these differently from
        // a shell, and the whole question is what the process a future engine would be actually holds.
        emit("jvm_version", System.getProperty("java.version"));
        emit("jvm_uid", readFirst("/proc/self/status", "Uid:"));
        emit("jvm_capabilities", capabilitiesEmpty() ? "EMPTY" : "PRESENT");
        emit("jvm_no_new_privs", readFirst("/proc/self/status", "NoNewPrivs:"));

        // WHAT A HOSTILE INTERPRETER WOULD REACH FOR FIRST. Java interop puts all of this one call away, so
        // the platform needs to know which of them the sandbox refuses and which it merely contains.
        emit("jvm_source_write", attempt(() -> {
            Files.writeString(Paths.get(SOURCE_ROOT, "hostile"), "x");
            return true;
        }));
        emit("jvm_source_exec", attempt(() -> {
            List<Path> files;
            try (var walk = Files.walk(Paths.get(SOURCE_ROOT, "files"))) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            if (files.isEmpty()) {
                return false;
            }
            Process direct = new ProcessBuilder(files.get(0).toString()).start();
            return direct.waitFor() == 0;
        }));
        emit("jvm_source_chmod", attempt(() -> {
            List<Path> files;
            try (var walk = Files.walk(Paths.get(SOURCE_ROOT, "files"))) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            return !files.isEmpty() && files.get(0).toFile().setExecutable(true);
        }));
        emit("jvm_remount", attempt(() -> {
            // No mount syscall from Java without JNI, so this is the reachable equivalent: ask the runtime to
            // do it. A JVM that could remount the source filesystem read-write would undo the freeze.
            Process mount = new ProcessBuilder("/bin/mount", "-o", "remount,rw", SOURCE_ROOT).start();
            return mount.waitFor() == 0;
        }));
        emit("jvm_mknod", attempt(() -> {
            Process mknod = new ProcessBuilder("/bin/mknod", SOURCE_ROOT + "/dev", "c", "1", "5").start();
            return mknod.waitFor() == 0;
        }));

        // WRITABLE FILESYSTEMS, INVENTORIED. noexec on the source filesystem says nothing about anywhere else,
        // and a workload that can write and then execute elsewhere is a workload that can run generated code.
        Path scratch = Paths.get(System.getProperty("java.io.tmpdir"), "kaas-hostile");
        emit("jvm_tmp_write", attempt(() -> {
            Files.writeString(scratch, "#!/bin/sh\necho HOSTILE_RAN\n");
            return true;
        }));
        emit("jvm_tmp_exec", attempt(() -> {
            scratch.toFile().setExecutable(true);
            Process generated = new ProcessBuilder(scratch.toString()).start();
            return generated.waitFor() == 0;
        }));

        // PROCESS CREATION. Not automatically a defect -- under a fully hostile model it is expected -- but
        // it must be understood and it must be contained.
        emit("jvm_child_spawn", attempt(() -> new ProcessBuilder("/bin/true").start().waitFor() == 0));

        // THREADS, because a JVM's unit of concurrency is not a process and the PID limit is expressed in
        // tasks. Bounded well below anything that would hurt the host: the question is whether the ceiling
        // exists, not how far past it we can get.
        AtomicInteger started = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                Thread thread = new Thread(() -> {
                    started.incrementAndGet();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                thread.setDaemon(true);
                thread.start();
                threads.add(thread);
            }
            emit("jvm_threads_started", threads.size());
        } catch (Throwable bounded) {
            // Being stopped is the expected outcome and is reported as the number reached, not as a crash.
            emit("jvm_threads_started", threads.size());
            emit("jvm_thread_limit", bounded.getClass().getSimpleName());
        }

        // NETWORK, WITHOUT ANY ENGINE'S HTTP CLIENT. The platform must be safe when tenant code ignores
        // Karate's networking entirely and opens a socket itself, which Java interop makes trivial.
        emit("jvm_dns", attempt(() -> {
            InetAddress.getByName("example.com");
            return true;
        }));
        emit("jvm_raw_socket_public", attempt(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("93.184.216.34", 80), 3000);
                return socket.isConnected();
            }
        }));
        emit("jvm_raw_socket_metadata", attempt(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("169.254.169.254", 80), 3000);
                return socket.isConnected();
            }
        }));
        emit("jvm_raw_socket_loopback", attempt(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 80), 1000);
                return socket.isConnected();
            }
        }));

        // WHAT THE PROCESS CAN SEE OF THE PLATFORM. A count, never the values: this output is collected by
        // the runner and a probe that printed an environment would print whatever a future defect put there.
        emit("jvm_environment_entries", System.getenv().size());
        emit("jvm_environment_names", String.join(",", new java.util.TreeSet<>(System.getenv().keySet())));
        emit("jvm_docker_socket", new File("/var/run/docker.sock").exists());

        // A CHILD THAT OUTLIVES THE PARENT, so cancellation can be tested against something that tries not to
        // die. It writes nothing and sleeps; the sandbox teardown is what has to remove it.
        if (args.length > 0 && "orphan".equals(args[0])) {
            new ProcessBuilder("/bin/sh", "-c", "sleep 600 &").inheritIO().start();
            emit("jvm_orphan_started", true);
            Thread.sleep(600_000);
        }

        emit("workload_identity", "KAAS_SYNTHETIC_V1");
        emit("workload_outcome", "PASSED");
    }

    /** Runs one attempt and reports only whether it succeeded. An exception is a refusal, not a crash. */
    private static boolean attempt(Attempt attempt) {
        try {
            return attempt.run();
        } catch (Throwable refused) {
            return false;
        }
    }

    private interface Attempt {
        boolean run() throws Exception;
    }

    private static boolean capabilitiesEmpty() {
        for (String field : List.of("CapInh:", "CapPrm:", "CapEff:", "CapBnd:", "CapAmb:")) {
            String value = readFirst("/proc/self/status", field);
            if (value == null || !value.replace("0", "").isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String readFirst(String file, String prefix) {
        try {
            for (String line : Files.readAllLines(Path.of(file))) {
                if (line.startsWith(prefix)) {
                    return line.substring(prefix.length()).trim();
                }
            }
        } catch (Exception unreadable) {
            return null;
        }
        return null;
    }
}
