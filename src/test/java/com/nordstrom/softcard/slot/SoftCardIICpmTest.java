package com.nordstrom.softcard.slot;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the Microsoft SoftCard II CP/M 2.28B master disk on the emulated
 * card and drives CP/M from the keyboard. Between them the steps exercise
 * the boot path (probe, opcode feeding, system load), console I/O
 * (commands 5-8 and 11), sector reads and writes (3 and 4) and warm boot (9).
 * The console text is recovered from the mailbox by {@link ConsoleTap}, so a
 * wrong entry in the command table fails these tests too.
 * <p>
 * The disk image is Microsoft's and isn't part of this project. Run with
 * {@code -PbootDisk=/path/to/image.dsk}; without it these tests are skipped.
 * The image is copied first, since the tests write to it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "softcard.bootDisk", matches = ".+")
class SoftCardIICpmTest {

    private static final long BOOT_CYCLES = 8_000_000;
    private static final long COMMAND_CYCLES = 25_000_000;

    private CpmMachine machine;

    @BeforeAll
    void boot(@TempDir Path scratch) throws IOException {
        Path copy = scratch.resolve("boot.dsk");
        Files.copy(Path.of(System.getProperty("softcard.bootDisk")), copy);
        machine = new CpmMachine(copy);
        machine.run(BOOT_CYCLES);
    }

    @Test
    @Order(1)
    void bootsToTheCcpPrompt() {
        String console = machine.console();
        assertTrue(console.contains("Softcard II CP/M"), console);
        assertTrue(console.contains("64K Version 2.28B"), console);
        assertTrue(console.endsWith("A>"), console);
    }

    @Test
    @Order(2)
    void listsTheDirectory() {
        String reply = command("DIR\n");
        assertTrue(reply.contains("PIP      COM"), reply);
        assertTrue(reply.contains("STAT     COM"), reply);
    }

    @Test
    @Order(3)
    void writesAndErasesAFile() {
        command("SAVE 1 TEST.COM\n");
        String stat = command("STAT TEST.COM\n");
        assertTrue(stat.contains("A:TEST.COM"), stat);
        command("ERA TEST.COM\n");
        String dir = command("DIR TEST.COM\n");
        assertTrue(dir.contains("No file"), dir);
    }

    @Test
    @Order(4)
    void warmBootsOnControlC() {
        command("\u0003");
        String stat = command("STAT\n");
        assertTrue(stat.contains("A: R/W, Space:"), stat);
    }

    private String command(String keys) {
        int mark = machine.console().length();
        machine.type(keys);
        machine.run(COMMAND_CYCLES);
        return machine.console().substring(mark);
    }
}
