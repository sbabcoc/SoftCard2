package com.nordstrom.softcard.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the board from the host side the way the CP/M loader's 6502 code
 * does. The probe sequence is the loader's own; the programs fed through
 * boot mode are written for these tests.
 */
class SoftCardBoardTest {

    /** Cycles a 6502 STA abs,Y plus loop overhead takes in the loader's unpolled probe loop. */
    private static final int PROBE_WRITE_SPACING = 14;

    /** Echo program for $8000: leave boot mode, then return every byte received plus one. */
    private static final int[] ECHO_PLUS_ONE = {
        0xD3, 0x00,       // 8000 OUT (0),A    ends boot mode
        0xDB, 0x01,       // 8002 IN A,(1)
        0x17,             // 8004 RLA          input-full bit into carry
        0x30, 0xFB,       // 8005 JR NC,8002
        0xDB, 0x00,       // 8007 IN A,(0)
        0x3C,             // 8009 INC A
        0x4F,             // 800A LD C,A
        0xDB, 0x01,       // 800B IN A,(1)
        0x1F,             // 800D RRA          output-full bit into carry
        0x38, 0xFB,       // 800E JR C,800B
        0x79,             // 8010 LD A,C
        0xD3, 0x00,       // 8011 OUT (0),A
        0x18, 0xED        // 8013 JR 8002
    };

    /** Counter program for $8000: leave boot mode, then count iterations of a 34 T-state loop into $9000. */
    private static final int[] COUNTER = {
        0xD3, 0x00,       // 8000 OUT (0),A
        0x21, 0x00, 0x00, // 8002 LD HL,0
        0x23,             // 8005 INC HL         6
        0x22, 0x00, 0x90, // 8006 LD (9000h),HL  16
        0x18, 0xFA        // 8009 JR 8005        12
    };

    @Test
    void startsInBootModeWithTheZ80WaitingOnTheMailbox() {
        SoftCardBoard board = new SoftCardBoard();
        board.advance(1000);
        assertTrue(board.isBootMode());
        assertTrue(board.cpu().isStalled());
        assertEquals(0, board.cpu().getPc());
    }

    @Test
    void answersTheLoadersProbeWithA5() {
        SoftCardBoard board = new SoftCardBoard();
        board.hostWrite(3, 0);
        board.hostRead(0);
        for (int b : new int[] {0x3E, 0x5A, 0x2F, 0x32, 0x00, 0x00}) { // LD A,5Ah / CPL / LD (0000h),A
            board.hostWrite(0, b);
            board.advance(PROBE_WRITE_SPACING);
        }
        assertEquals(Mailbox.INPUT_FULL, board.hostRead(1) & Mailbox.INPUT_FULL);
        assertEquals(0xA5, board.hostRead(0));
    }

    @Test
    void runsAFedProgramFromRamAndLeavesBootModeWithoutLatching() {
        SoftCardBoard board = bootWith(ECHO_PLUS_ONE);
        assertFalse(board.isBootMode());
        assertEquals(0xD3, board.peekRam(0x8000));
        assertEquals(0, board.hostRead(1) & Mailbox.INPUT_FULL, "the boot-mode OUT must not hand the 6502 a byte");

        send(board, 0x41);
        assertEquals(0x42, receive(board));
        send(board, 0xFF);
        assertEquals(0x00, receive(board));
    }

    @Test
    void writesToTheStatusAddressChangeNothing() {
        SoftCardBoard board = bootWith(ECHO_PLUS_ONE);
        int before = board.hostRead(1);
        board.hostWrite(1, 0xFF);
        assertEquals(before, board.hostRead(1));
        send(board, 0x10);
        assertEquals(0x11, receive(board));
    }

    @Test
    void writeToC0n3ResetsIntoBootMode() {
        SoftCardBoard board = bootWith(ECHO_PLUS_ONE);
        board.hostWrite(3, 0);
        board.advance(100);
        assertTrue(board.isBootMode());
        assertEquals(0, board.cpu().getPc());
    }

    @Test
    void runsAtTheConfiguredClockRelativeToTheHost() {
        SoftCardBoard board = bootWith(COUNTER);
        board.advance(1000);
        int start = counter(board);
        int hostCycles = 1_020_484; // about one second of Apple II time
        board.advance(hostCycles);
        long iterations = counter(board) - start & 0xFFFF;
        long expectedTStates = (long) hostCycles * SoftCardBoard.DEFAULT_Z80_CLOCK_HZ * 912 / (14_318_180L * 65);
        long expectedIterations = expectedTStates / 34 & 0xFFFF;
        assertTrue(Math.abs(iterations - expectedIterations) <= 1,
                "expected about " + expectedIterations + " loop iterations (mod 65536), got " + iterations);
    }

    /** Resets the board and feeds it LD HL,8000h / LD (HL),n / INC HL ... / JP 8000h, as the CP/M loader does. */
    private static SoftCardBoard bootWith(int[] program) {
        SoftCardBoard board = new SoftCardBoard();
        board.hostWrite(3, 0);
        send(board, 0x21);
        send(board, 0x00);
        send(board, 0x80);
        for (int b : program) {
            send(board, 0x36);
            send(board, b);
            send(board, 0x23);
        }
        send(board, 0xC3);
        send(board, 0x00);
        send(board, 0x80);
        board.advance(200);
        return board;
    }

    /** The loader's send: wait for the previous byte to be taken, then write. */
    private static void send(SoftCardBoard board, int value) {
        waitFor(board, () -> (board.hostRead(1) & Mailbox.OUTPUT_FULL) == 0);
        board.hostWrite(0, value);
        board.advance(8);
    }

    /** The server's receive: wait for a byte, then read it. */
    private static int receive(SoftCardBoard board) {
        waitFor(board, () -> (board.hostRead(1) & Mailbox.INPUT_FULL) != 0);
        return board.hostRead(0);
    }

    private static void waitFor(SoftCardBoard board, java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 10_000; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            board.advance(10);
        }
        throw new AssertionError("timed out waiting on the mailbox");
    }

    private static int counter(SoftCardBoard board) {
        return board.peekRam(0x9000) | board.peekRam(0x9001) << 8;
    }
}
