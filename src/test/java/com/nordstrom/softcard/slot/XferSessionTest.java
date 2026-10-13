package com.nordstrom.softcard.slot;

import com.nordstrom.emulator.transfer.GuestEntry;
import com.nordstrom.emulator.transfer.ResultCode;
import com.nordstrom.emulator.transfer.TransferHost;
import com.nordstrom.emulator.transfer.TransferOperations;
import com.nordstrom.emulator.transfer.TransferSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole transfer session with ReplicApple2Plus's CP/M adapter, XFER, on
 * the SoftCard II master disk: the host side asks through
 * {@link TransferOperations}, exactly as the transfer window does, and XFER
 * answers with BDOS calls through the host file transfer card in slot 2.
 * <p>
 * The machine runs on its own thread while the requests wait on their
 * answers, as the emulator's emulation thread and the window's worker do.
 * Needs the master disk, like {@link SoftCard2CpmTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "softcard.bootDisk", matches = ".+")
class XferSessionTest {

    private static final long BOOT_CYCLES = 8_000_000;
    private static final long COMMAND_CYCLES = 25_000_000;
    private static final List<String> A0 = List.of("A0:");

    @TempDir
    static Path host;

    private CpmMachine machine;
    private Thread running;
    private volatile boolean stop;
    private final BlockingQueue<TransferSession> started = new ArrayBlockingQueue<>(1);
    private TransferOperations ops;

    /** Answers every conflict with "replace". */
    private final TransferOperations.Conflicts replace = new TransferOperations.Conflicts() {
        @Override
        public boolean replaceGuestFile(String guestName) {
            return true;
        }

        @Override
        public boolean overwriteHostFile(Path hostFile) {
            return true;
        }
    };

    @BeforeAll
    void startSession(@TempDir Path scratch) throws Exception {
        Path copy = scratch.resolve("boot.dsk");
        Files.copy(Path.of(System.getProperty("softcard.bootDisk")), copy);
        assertTrue(copy.toFile().setWritable(true), "could not make the disk copy writable: " + copy);
        machine = new CpmMachine(copy);
        machine.run(BOOT_CYCLES);
        command("PIP XFER.HEX=RDR:\n");
        command("LOAD XFER\n");
        command("ERA XFER.HEX\n");

        machine.transferCard().setHost(new TransferHost() {
            @Override
            public void sessionStarted(TransferSession session) {
                started.add(session);
            }

            @Override
            public void sessionEnded(TransferSession session, EndReason reason) {
            }
        });
        machine.type("XFER\n");
        running = new Thread(() -> {
            while (!stop) {
                machine.run(100_000);
            }
        }, "cpm");
        running.start();
        TransferSession session = started.poll(60, TimeUnit.SECONDS);
        assertNotNull(session, "XFER never began a session:\n" + machine.console());
        assertEquals("CP/M", session.capabilities().osName());
        ops = new TransferOperations(session);
    }

    @AfterAll
    void stopMachine() throws InterruptedException {
        stop = true;
        if (running != null) {
            running.join();
        }
    }

    @Test
    @Order(1)
    void volumesAreTheDrivesWithDisksByUserArea() throws Exception {
        // Drive B is empty, so it isn't offered. The master disk has one file
        // outside area 0: APDOS.$$$, a leftover temporary file in area 6.
        assertEquals(List.of("A0:", "A6:"), ops.volumes());
    }

    @Test
    @Order(2)
    void listsTheMasterDisk() throws Exception {
        GuestEntry pip = entry("PIP.COM");
        assertTrue(pip.size() > 0 && pip.size() % 128 == 0, "whole records: " + pip.size());
        assertNotNull(entry("XFER.COM"));
        assertEquals("", pip.type().tag(), "CP/M files carry no type");
    }

    @Test
    @Order(3)
    void exportsSourceAsTextAndProgramsAsTheyAre() throws Exception {
        TransferOperations.ExportRead source = ops.readForExport(List.of("A0:", "DUMP.ASM"), entry("DUMP.ASM"));
        assertTrue(source.succeeded(), String.valueOf(source.failure()));
        assertEquals("DUMP.ASM.TXT", source.hostName());
        String text = new String(source.data(), StandardCharsets.US_ASCII);
        assertTrue(text.indexOf('\r') < 0 && text.indexOf(0x1A) < 0, "CR LF became LF, and the ^Z ended it");

        GuestEntry pip = entry("PIP.COM");
        TransferOperations.ExportRead program = ops.readForExport(List.of("A0:", "PIP.COM"), pip);
        assertEquals("PIP.COM", program.hostName());
        assertEquals(pip.size(), program.data().length);
    }

    @Test
    @Order(4)
    void textRoundTripsThroughTheDisk() throws Exception {
        Path source = host.resolve("PROG.ASM.TXT");
        Files.writeString(source, "HELLO\nFROM THE HOST\n");
        TransferOperations.Result r = ops.importFile(source, A0, "PROG.ASM", replace);
        assertEquals(TransferOperations.Outcome.DONE, r.outcome(), r.detail());
        GuestEntry entry = entry("PROG.ASM");
        assertEquals(128, entry.size());
        TransferOperations.ExportRead back = ops.readForExport(List.of("A0:", "PROG.ASM"), entry);
        assertEquals("PROG.ASM.TXT", back.hostName());
        assertEquals("HELLO\nFROM THE HOST\n", new String(back.data(), StandardCharsets.US_ASCII));
    }

    @Test
    @Order(5)
    void aFileWithoutATextExtensionComesBackAsTextOnlyByOverride() throws Exception {
        Path notes = host.resolve("NOTES.TXT");
        Files.writeString(notes, "HELLO\n");
        ops.importFile(notes, A0, "NOTES", replace); // .TXT marks text; the CP/M name is what remains
        GuestEntry entry = entry("NOTES");
        assertEquals("NOTES", ops.readForExport(List.of("A0:", "NOTES"), entry).hostName(), "binary by default");
        TransferOperations.ExportRead asText = ops.readForExport(List.of("A0:", "NOTES"), entry, true);
        assertEquals("NOTES.TXT", asText.hostName());
        assertEquals("HELLO\n", new String(asText.data(), StandardCharsets.US_ASCII));
    }

    @Test
    @Order(6)
    void binaryRoundTripsWithPadding() throws Exception {
        byte[] data = new byte[300];
        new Random(42).nextBytes(data);
        Path game = host.resolve("GAME.COM");
        Files.write(game, data);
        TransferOperations.Result r = ops.importFile(game, A0, "GAME.COM", replace);
        assertEquals(TransferOperations.Outcome.DONE, r.outcome(), r.detail());
        TransferOperations.ExportRead back = ops.readForExport(List.of("A0:", "GAME.COM"), entry("GAME.COM"));
        assertEquals(384, back.data().length);
        assertArrayEquals(data, Arrays.copyOf(back.data(), 300));
        for (int i = 300; i < 384; i++) {
            assertEquals(0x1A, back.data()[i] & 0xFF);
        }
    }

    @Test
    @Order(7)
    void replacesAnExistingFileWhenTold() throws Exception {
        Path source = host.resolve("PROG.ASM.TXT");
        Files.writeString(source, "REPLACED\n");
        TransferOperations.Result r = ops.importFile(source, A0, "PROG.ASM", replace);
        assertEquals(TransferOperations.Outcome.DONE, r.outcome(), r.detail());
        TransferOperations.ExportRead back = ops.readForExport(List.of("A0:", "PROG.ASM"), entry("PROG.ASM"));
        assertEquals("REPLACED\n", new String(back.data(), StandardCharsets.US_ASCII));
    }

    @Test
    @Order(8)
    void deletesAFileBeforeReplacingIt() throws Exception {
        assertTrue(ops.list(A0).stream().anyMatch(e -> e.name().equals("GAME.COM")));
        ops.importFile(host.resolve("GAME.COM"), A0, "GAME.COM", replace); // EXISTS, then DELETE and WRITE
        assertEquals(1, ops.list(A0).stream().filter(e -> e.name().equals("GAME.COM")).count());
    }

    @Test
    @Order(9)
    void checksWhereFilesCanGo() {
        assertEquals(ResultCode.OK, ops.checkWritable(A0));
        assertEquals(ResultCode.NOT_FOUND, ops.checkWritable(List.of("B0:")), "drive B is empty");
    }

    @Test
    @Order(10)
    void endingReturnsToTheCcp() throws Exception {
        ops.end();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!machine.console().endsWith("A>") && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        String console = machine.console();
        assertTrue(console.contains("HOST TRANSFER: CP/M 2.2"), console);
        assertTrue(console.contains("HOST TRANSFER ENDED"), console);
        assertTrue(console.endsWith("A>"), console);
    }

    private GuestEntry entry(String name) throws Exception {
        return ops.list(A0).stream().filter(e -> e.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(name + " not listed"));
    }

    private void command(String keys) {
        machine.type(keys);
        machine.run(COMMAND_CYCLES);
    }
}
