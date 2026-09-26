package com.kaas.runner.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Removes exact secret byte sequences from ONE output stream, whatever the chunking.
 *
 * <h2>What it guarantees, exactly</h2>
 *
 * <p>Every occurrence of every registered value, as the exact byte sequence it was delivered as, is replaced
 * before any byte of it is emitted — including an occurrence split across any number of chunks, one that begins
 * in the last bytes a stream ever produces, and one that overlaps another registered value. Nothing else is
 * claimed. A value that tenant code transforms (encodes, hashes, reverses, splits with separators, prints one
 * character per line) is a different byte sequence and is not detected; that is intentional, documented in
 * docs/security/secret-redaction-boundary.md, and is why this is not called data-loss prevention.
 *
 * <h2>How</h2>
 *
 * <p>An Aho-Corasick automaton over bytes finds every occurrence of every value in one pass, in time linear in
 * the input, whatever the values are — so a tenant who knows its own secret cannot make the runner quadratic by
 * printing it overlapping itself. Each match marks the interval of bytes it covers; overlapping and adjacent
 * intervals merge, so {@code abc} and {@code abcdef} registered together can never leave {@code def} behind, and
 * a run of covered bytes becomes ONE {@link #REPLACEMENT}.
 *
 * <p>A byte is emitted only once no future match could still cover it: once {@code longest - 1} further bytes
 * have arrived, or the stream has ended. That holdback is the whole mechanism by which a chunk boundary cannot
 * split a secret, and it is also why redaction happens BEFORE the output ceiling rather than after it — the
 * ceiling counts emitted bytes, and nothing unredacted is ever emitted to be counted.
 *
 * <h2>What the replacement says</h2>
 *
 * <p>{@code [REDACTED]} and nothing else: not the binding key, not the length, not the version, not which of
 * several values matched. Any of those would make the redaction itself a channel.
 *
 * <p>Not thread-safe; one instance per stream, driven by the thread that receives that stream's frames.
 */
public final class SecretRedactor {

    public static final byte[] REPLACEMENT = "[REDACTED]".getBytes(StandardCharsets.US_ASCII);

    /** Where emitted bytes go. */
    @FunctionalInterface
    public interface Sink {
        void accept(byte[] bytes, int offset, int length);
    }

    private final Automaton automaton;

    /** Bytes received and not yet emitted, from absolute position {@link #emitted}. */
    private final ByteQueue pending = new ByteQueue();

    /** Merged covered intervals, [start, end] inclusive, absolute positions, ascending. */
    private final ArrayDeque<long[]> covered = new ArrayDeque<>();

    private int state;
    private long position;
    private long emitted;
    private boolean insideRedaction;
    private long matches;
    private boolean finished;

    private SecretRedactor(Automaton automaton) {
        this.automaton = automaton;
    }

    /**
     * A redactor for one stream, over the given values.
     *
     * <p>The values are copied into the automaton, which is shared by nothing and cleared by {@link #close()}.
     */
    public static SecretRedactor over(List<byte[]> values) {
        return new SecretRedactor(Automaton.of(values));
    }

    /** A redactor with nothing to find: every byte is emitted as it arrives. */
    public static SecretRedactor none() {
        return new SecretRedactor(Automaton.of(List.of()));
    }

    /** How many raw occurrences were found and replaced. Trusted evidence that the hazard happened. */
    public long matches() {
        return matches;
    }

    public void write(byte[] data, int offset, int length, Sink sink) {
        if (finished) {
            throw new IllegalStateException("The stream already ended.");
        }
        int longest = automaton.longest;
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + REPLACEMENT.length);
        for (int index = offset; index < offset + length; index++) {
            byte value = data[index];
            pending.add(value);
            long here = position++;
            if (longest == 0) {
                continue;
            }
            state = automaton.next(state, value);
            int matched = automaton.longestEndingAt(state);
            if (matched > 0) {
                matches++;
                cover(here - matched + 1, here);
            }
            // Final once no match ending at a future position could reach back to it.
            emitThrough(position - longest, out);
        }
        if (longest == 0) {
            emitThrough(position - 1, out);
        }
        flush(out, sink);
    }

    /** Ends the stream: everything held back is now final. */
    public void finish(Sink sink) {
        if (finished) {
            return;
        }
        finished = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream(REPLACEMENT.length + 64);
        emitThrough(position - 1, out);
        flush(out, sink);
    }

    /** Clears the automaton's copy of the values and anything still held back. */
    public void close() {
        automaton.clear();
        pending.clear();
    }

    private void cover(long start, long end) {
        // A later match can reach further back than an earlier one (a longer value ending later), so the new
        // interval may swallow several already recorded. Ends only grow, so only the tail can be affected.
        long mergedStart = start;
        long mergedEnd = end;
        while (!covered.isEmpty() && covered.peekLast()[1] + 1 >= mergedStart) {
            long[] last = covered.pollLast();
            mergedStart = Math.min(mergedStart, last[0]);
            mergedEnd = Math.max(mergedEnd, last[1]);
        }
        covered.addLast(new long[] {mergedStart, mergedEnd});
    }

    /** Emits every pending byte at absolute positions up to and including {@code last}. */
    private void emitThrough(long last, ByteArrayOutputStream out) {
        while (emitted <= last && pending.size() > 0) {
            while (!covered.isEmpty() && covered.peekFirst()[1] < emitted) {
                covered.pollFirst();
            }
            byte value = pending.poll();
            boolean isCovered = !covered.isEmpty() && covered.peekFirst()[0] <= emitted;
            if (isCovered) {
                if (!insideRedaction) {
                    out.writeBytes(REPLACEMENT);
                    insideRedaction = true;
                }
            } else {
                insideRedaction = false;
                out.write(value);
            }
            emitted++;
        }
    }

    private static void flush(ByteArrayOutputStream out, Sink sink) {
        if (out.size() == 0) {
            return;
        }
        byte[] bytes = out.toByteArray();
        sink.accept(bytes, 0, bytes.length);
    }

    /** A growable FIFO of bytes, cleared on demand. */
    private static final class ByteQueue {
        private byte[] buffer = new byte[256];
        private int head;
        private int size;

        void add(byte value) {
            if (size == buffer.length) {
                byte[] grown = new byte[buffer.length * 2];
                for (int index = 0; index < size; index++) {
                    grown[index] = buffer[(head + index) % buffer.length];
                }
                Arrays.fill(buffer, (byte) 0);
                buffer = grown;
                head = 0;
            }
            buffer[(head + size) % buffer.length] = value;
            size++;
        }

        byte poll() {
            byte value = buffer[head];
            buffer[head] = 0;
            head = (head + 1) % buffer.length;
            size--;
            return value;
        }

        int size() {
            return size;
        }

        void clear() {
            Arrays.fill(buffer, (byte) 0);
            head = 0;
            size = 0;
        }
    }

    /**
     * An Aho-Corasick automaton over bytes, reporting the LONGEST value ending at each state.
     *
     * <p>Only the longest matters: every shorter value ending at the same position lies inside it, so its
     * interval is covered already.
     */
    private static final class Automaton {
        private final List<Node> nodes = new ArrayList<>();
        private int longest;

        static Automaton of(List<byte[]> values) {
            Automaton automaton = new Automaton();
            automaton.nodes.add(new Node());
            for (byte[] value : values) {
                if (value == null || value.length == 0) {
                    // An empty value would match everywhere and redact everything. The platform never delivers
                    // one, and it is refused here too rather than trusted to be absent.
                    throw new IllegalArgumentException("An empty value cannot be redacted.");
                }
                int current = 0;
                for (byte b : value) {
                    int child = automaton.nodes.get(current).child(b);
                    if (child < 0) {
                        child = automaton.nodes.size();
                        automaton.nodes.add(new Node());
                        automaton.nodes.get(current).put(b, child);
                    }
                    current = child;
                }
                Node terminal = automaton.nodes.get(current);
                terminal.length = Math.max(terminal.length, value.length);
                automaton.longest = Math.max(automaton.longest, value.length);
            }
            automaton.link();
            return automaton;
        }

        private void link() {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            Node root = nodes.get(0);
            for (int index = 0; index < root.size; index++) {
                Node child = nodes.get(root.targets[index]);
                child.failure = 0;
                child.output = child.length;
                queue.add(root.targets[index]);
            }
            while (!queue.isEmpty()) {
                int current = queue.poll();
                Node node = nodes.get(current);
                for (int index = 0; index < node.size; index++) {
                    byte b = node.keys[index];
                    int target = node.targets[index];
                    int fallback = node.failure;
                    while (fallback > 0 && nodes.get(fallback).child(b) < 0) {
                        fallback = nodes.get(fallback).failure;
                    }
                    int candidate = nodes.get(fallback).child(b);
                    Node child = nodes.get(target);
                    child.failure = candidate >= 0 && candidate != target ? candidate : 0;
                    child.output = Math.max(child.length, nodes.get(child.failure).output);
                    queue.add(target);
                }
            }
        }

        int next(int state, byte b) {
            int current = state;
            while (true) {
                int child = nodes.get(current).child(b);
                if (child >= 0) {
                    return child;
                }
                if (current == 0) {
                    return 0;
                }
                current = nodes.get(current).failure;
            }
        }

        int longestEndingAt(int state) {
            return nodes.get(state).output;
        }

        void clear() {
            for (Node node : nodes) {
                Arrays.fill(node.keys, (byte) 0);
            }
            nodes.clear();
            nodes.add(new Node());
            longest = 0;
        }
    }

    private static final class Node {
        private byte[] keys = new byte[2];
        private int[] targets = new int[2];
        private int size;
        private int failure;
        private int length;
        private int output;

        int child(byte b) {
            for (int index = 0; index < size; index++) {
                if (keys[index] == b) {
                    return targets[index];
                }
            }
            return -1;
        }

        void put(byte b, int target) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                targets = Arrays.copyOf(targets, size * 2);
            }
            keys[size] = b;
            targets[size] = target;
            size++;
        }
    }

    @Override
    public String toString() {
        return "SecretRedactor[matches=" + matches + "]";
    }
}
