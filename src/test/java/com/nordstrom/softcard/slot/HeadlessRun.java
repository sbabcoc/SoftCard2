package com.nordstrom.softcard.slot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a script on ReplicApple2Plus's headless runner with the real
 * Apple II+ ROMs, as a JVM of its own on this project's test class path.
 * The card is found there the way a plug-in jar's card is, through its
 * service registration, so no plug-ins folder is needed. The arguments are
 * the runner's own command line; the screen comes back as text.
 * <p>
 * A separate JVM because the runner's entry point is its command line, and
 * because each run then starts from a clean JVM, as a run from a Makefile
 * does.
 * <p>
 * The ROM images aren't part of either project: run with
 * {@code -Proms=/path/to/roms}, the folder holding {@code system-rom.rom}
 * and the VideoTerm's two ROMs. Tests that need it check for it with
 * {@code @EnabledIfSystemProperty(named = HeadlessRun.ROMS, matches = ".+")}.
 */
final class HeadlessRun {

    /** System property naming the ROM folder, from {@code -Proms}. */
    static final String ROMS = "runner.roms";
    /** System property holding the test class path, set by the build. */
    static final String CLASSPATH = "runner.classpath";

    /** Wall-clock limit for one run; the runner's own timeouts are in emulated time. */
    private static final long LIMIT_MINUTES = 5;

    /**
     * How a run ended.
     *
     * @param status the runner's exit status: 0 finished, 1 setup, 2 didn't boot, 3 timed out, 4 stopped
     * @param screen the screen's text at the end
     * @param errors what the runner reported
     */
    record Result(int status, String screen, String errors) {

        /** @return the whole result, for an assertion's message */
        String describe() {
            return "runner status " + status + "\n" + screen + "\n" + errors;
        }
    }

    private HeadlessRun() {
    }

    /**
     * @param args the runner's arguments, ROM folder aside
     * @return how the run ended
     */
    static Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=true",
            "-cp", System.getProperty(CLASSPATH),
            "com.nordstrom.emulator.HeadlessRunner",
            "--roms", System.getProperty(ROMS)));
        command.addAll(List.of(args));
        // files rather than pipes, so neither stream can fill and stall the runner
        Path out = Files.createTempFile("runner", ".txt");
        Path err = Files.createTempFile("runner", ".err");
        try {
            Process process = new ProcessBuilder(command)
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
            if (!process.waitFor(LIMIT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError("the runner was still going after " + LIMIT_MINUTES + " minutes\n"
                    + Files.readString(err, StandardCharsets.ISO_8859_1));
            }
            return new Result(process.exitValue(),
                Files.readString(out, StandardCharsets.ISO_8859_1),
                Files.readString(err, StandardCharsets.ISO_8859_1));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }
}
