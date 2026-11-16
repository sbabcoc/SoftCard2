package com.nordstrom.softcard.slot;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CP/M master disk on a whole Apple II+: ReplicApple2Plus's headless
 * runner with the real Autostart ROM, the real VideoTerm as CP/M's console
 * (its own firmware, reached through the card's $C800 entry), a Language
 * Card and a Disk II, and this card in slot 4 -- the machine in
 * ReplicApple2Plus's README for running CP/M. Each test is one run, from
 * power-on to CP/M's {@code A>} prompt and on through its keys, read back
 * off the VideoTerm's screen.
 * <p>
 * {@link SoftCard2CpmTest} drives the same commands on a stand-in machine
 * that runs anywhere; these show the card working where it will be used.
 * Needs the master disk ({@code -PbootDisk}) and the ROM images
 * ({@code -Proms}, see {@link HeadlessRun}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "softcard.bootDisk", matches = ".+")
@EnabledIfSystemProperty(named = HeadlessRun.ROMS, matches = ".+")
class SoftCard2RunnerTest {

    private Path config;

    @BeforeAll
    void writeConfiguration(@TempDir Path scratch) throws IOException {
        Path disk = scratch.resolve("boot.dsk");
        Files.copy(Path.of(System.getProperty("softcard.bootDisk")), disk);
        // The runner works on a copy of the image, which keeps the original's
        // permissions; a read-only original would be a write-protected disk.
        assertTrue(disk.toFile().setWritable(true), "could not make the disk copy writable: " + disk);
        config = scratch.resolve("cpm.ini");
        Files.writeString(config, String.join("\n",
            "[0]", "type=languageCard", "",
            "[3]", "type=videoterm", "",
            "[4]", "type=softcard2", "",
            "[6]", "type=disk2", "drive1=" + disk.getFileName(), ""));
    }

    @Test
    void bootsToTheCcpAndListsTheDirectory() throws Exception {
        HeadlessRun.Result run = cpm("--type", "DIR\\r", "--until", "STAT     COM");
        assertEquals(0, run.status(), run.describe());
        assertTrue(run.screen().contains("Softcard II CP/M"), run.describe());
        assertTrue(run.screen().contains("64K Version 2.28B"), run.describe());
        assertTrue(run.screen().contains("PIP      COM"), run.describe());
    }

    @Test
    void writesAndErasesAFile() throws Exception {
        HeadlessRun.Result run = cpm(
            "--type", "SAVE 1 TEST.COM\\r", "--wait", "2",
            "--type", "STAT TEST.COM\\r", "--expect", "A:TEST.COM",
            "--type", "ERA TEST.COM\\r", "--wait", "2",
            "--type", "DIR TEST.COM\\r", "--until", "No file");
        assertEquals(0, run.status(), run.describe());
    }

    @Test
    void warmBootsOnControlC() throws Exception {
        HeadlessRun.Result run = cpm("--type", "\\x03", "--wait", "2", "--type", "STAT\\r", "--until", "Space:");
        assertEquals(0, run.status(), run.describe());
        assertTrue(run.screen().contains("A: R/W, Space:"), run.describe());
    }

    /** @param script the runner's steps after the boot to {@code A>} */
    private HeadlessRun.Result cpm(String... script) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("--config", config.toString(), "--prompt", "A>"));
        args.addAll(List.of(script));
        return HeadlessRun.run(args.toArray(String[]::new));
    }
}
