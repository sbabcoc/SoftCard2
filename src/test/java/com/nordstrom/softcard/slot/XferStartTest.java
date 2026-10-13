package com.nordstrom.softcard.slot;

import com.nordstrom.emulator.transfer.CpmTransferStarter;
import com.nordstrom.emulator.transfer.TransferHost;
import com.nordstrom.emulator.transfer.TransferOperations;
import com.nordstrom.emulator.transfer.TransferSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReplicApple2Plus's File &gt; Start CP/M Transfer logic,
 * {@link CpmTransferStarter}, typing at the real master disk's CCP: it
 * installs XFER through the transfer card's reader and starts a session,
 * starts it again without reinstalling, and explains a missing tool.
 * Needs the master disk, like {@link SoftCard2CpmTest}.
 */
@EnabledIfSystemProperty(named = "softcard.bootDisk", matches = ".+")
class XferStartTest {

    private static final long BOOT_CYCLES = 8_000_000;

    @TempDir
    Path scratch;

    private CpmMachine machine;
    private Thread running;
    private volatile boolean stop;
    private final BlockingQueue<TransferSession> started = new ArrayBlockingQueue<>(4);

    @AfterEach
    void stopMachine() throws InterruptedException {
        stop = true;
        if (running != null) {
            running.join();
        }
    }

    @Test
    void installsXferThenStartsItAndNextTimeJustStartsIt() throws Exception {
        boot();
        assertTrue(machine.transferCard().servedByCpm(), "CP/M initialized the card: the menu item is enabled");

        CpmTransferStarter.Result first = starter().run();
        assertTrue(first.started(), first.message() + "\n" + machine.console());
        assertTrue(machine.console().contains("RECORDS WRITTEN"), "it installed XFER");
        TransferSession session = started.poll(30, TimeUnit.SECONDS);
        assertNotNull(session, "a session began");
        new TransferOperations(session).end();
        awaitPrompt();

        int mark = machine.console().length();
        CpmTransferStarter.Result second = starter().run();
        assertTrue(second.started(), second.message());
        assertFalse(machine.console().substring(mark).contains("PIP"), "already installed: no PIP this time");
        new TransferOperations(started.poll(30, TimeUnit.SECONDS)).end();
    }

    @Test
    void explainsAMissingPip() throws Exception {
        bootWithout("PIP.COM");
        CpmTransferStarter.Result result = starter().run();
        assertFalse(result.started());
        assertTrue(result.message().contains("PIP.COM"), result.message());
    }

    private CpmTransferStarter starter() {
        CpmTransferStarter.Keyboard keyboard = new CpmTransferStarter.Keyboard() {
            @Override
            public void type(String text) {
                machine.type(text.replace('\r', '\n'));
            }

            @Override
            public void press(int key) {
                machine.type(String.valueOf((char) key));
            }

            @Override
            public boolean isTyping() {
                return machine.isTyping();
            }
        };
        return new CpmTransferStarter(keyboard, machine::console, 60_000, 200);
    }

    private void boot() throws IOException {
        Path copy = scratch.resolve("boot.dsk");
        Files.copy(Path.of(System.getProperty("softcard.bootDisk")), copy);
        assertTrue(copy.toFile().setWritable(true), "could not make the disk copy writable: " + copy);
        machine = new CpmMachine(copy);
        machine.run(BOOT_CYCLES);
        machine.transferCard().setHost(new TransferHost() {
            @Override
            public void sessionStarted(TransferSession session) {
                started.add(session);
            }

            @Override
            public void sessionEnded(TransferSession session, EndReason reason) {
            }
        });
        running = new Thread(() -> {
            while (!stop) {
                machine.run(100_000);
            }
        }, "cpm");
        running.start();
    }

    private void bootWithout(String file) throws Exception {
        boot();
        machine.type("ERA " + file + "\n");
        awaitPrompt();
    }

    private void awaitPrompt() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            Thread.sleep(200);
        } while (!(machine.console().endsWith("A>") && !machine.isTyping()) && System.nanoTime() < deadline);
    }
}
