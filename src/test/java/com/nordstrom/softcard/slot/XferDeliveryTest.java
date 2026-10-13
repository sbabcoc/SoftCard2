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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivers ReplicApple2Plus's CP/M adapter, XFER, through the host file
 * transfer card in slot 2, using only programs on the CP/M master disk
 * (ReplicApple2Plus TRANSFER-CARD.md 8.2). This checks the whole path the
 * design depends on: CP/M's loader recognizing the card as a 6850 serial
 * card from its identification bytes, the 6502 server reading its 6850 for
 * CP/M's reader device, PIP copying the Intel HEX to disk up to the ^Z, and
 * LOAD accepting what sjasmplus emitted.
 * <p>
 * Needs the master disk, like {@link SoftCard2CpmTest}: run with
 * {@code -PbootDisk=/path/to/image.dsk}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "softcard.bootDisk", matches = ".+")
class XferDeliveryTest {

    private static final long BOOT_CYCLES = 8_000_000;
    private static final long COMMAND_CYCLES = 25_000_000;

    private CpmMachine machine;

    @BeforeAll
    void boot(@TempDir Path scratch) throws IOException {
        Path copy = scratch.resolve("boot.dsk");
        Files.copy(Path.of(System.getProperty("softcard.bootDisk")), copy);
        assertTrue(copy.toFile().setWritable(true), "could not make the disk copy writable: " + copy);
        machine = new CpmMachine(copy);
        machine.run(BOOT_CYCLES);
        assertTrue(machine.console().endsWith("A>"), machine.console());
    }

    @Test
    @Order(1)
    void pipCopiesTheAdapterFromTheReader() {
        command("PIP XFER.HEX=RDR:\n");
        String dir = command("DIR XFER.HEX\n");
        assertTrue(dir.contains("XFER     HEX"), dir);
    }

    @Test
    @Order(2)
    void loadTurnsItIntoACommand() {
        String load = command("LOAD XFER\n");
        assertFalse(load.contains("?"), load);
        String dir = command("DIR XFER.COM\n");
        assertTrue(dir.contains("XFER     COM"), dir);
    }

    @Test
    @Order(3)
    void theAdapterRuns() {
        String run = command("XFER\n");
        assertTrue(run.contains("XFER: the CP/M adapter isn't built yet."), run);
        assertTrue(run.endsWith("A>"), run);
    }

    @Test
    @Order(4)
    void aSecondCopyIsWholeToo() {
        command("ERA XFER.*\n");
        command("PIP XFER.HEX=RDR:\n");
        command("LOAD XFER\n");
        String run = command("XFER\n");
        assertTrue(run.contains("XFER: the CP/M adapter isn't built yet."), run);
    }

    private String command(String keys) {
        int mark = machine.console().length();
        machine.type(keys);
        machine.run(COMMAND_CYCLES);
        return machine.console().substring(mark);
    }
}
