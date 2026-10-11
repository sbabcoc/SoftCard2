package com.nordstrom.softcard.board;

import com.nordstrom.softcard.z80.WaitState;
import com.nordstrom.softcard.z80.Z80;
import com.nordstrom.softcard.z80.Z80Bus;

/**
 * The Microsoft SoftCard II as what it is: a single-board computer. A Z80B
 * runs from its own 64K of DRAM on its own clock; the only thing it shares
 * with the Apple II is the {@link Mailbox}. Everything the host computer can
 * do to the board is in the four {@code host*} methods plus {@link #reset}
 * and {@link #advance}; nothing here knows what an Apple II is.
 * <p>
 * <b>Boot mode.</b> After a reset the board starts in boot mode, in which
 * Z80 memory accesses with A15 low go to the mailbox instead of RAM: reads
 * take the 6502's byte, writes hand a byte to the 6502. That is how the
 * 6502 starts a Z80 that has no ROM: it feeds opcodes one byte at a time.
 * Accesses with A15 high reach RAM normally, so the fed code can build a
 * program at $8000 and jump to it. The first Z80 I/O write ends boot mode
 * and latches nothing. All of this is inferred from the boot disk's code
 * (see HARDWARE-REFERENCE.md); in particular A15 is the simplest decode the
 * code is consistent with, not a traced one.
 * <p>
 * <b>Waiting.</b> A Z80 read from an empty mailbox in boot mode stalls the
 * CPU (on the board, the input flag must drive /WAIT: the 6502 feeds
 * opcodes far more slowly than the Z80 fetches them). {@link Z80#step}
 * models the stall by retrying the instruction, so this class replays the
 * bytes an instruction already took from the mailbox when it retries. That
 * reproduces /WAIT exactly because every Z80 instruction finishes its reads
 * before its first write: a stall can only land on a read, so the attempt
 * being abandoned has written nothing.
 */
public final class SoftCardBoard {

    /** Apple II CPU clock: 14.31818 MHz / 14, with one stretched cycle in every 65. */
    private static final long HOST_CLOCK_NUMERATOR = 14_318_180L * 65;
    private static final long HOST_CLOCK_DENOMINATOR = 912;

    /** The Z80 clock this board is assumed to run at; see the constructor. */
    public static final int DEFAULT_Z80_CLOCK_HZ = 6_000_000;

    private final int[] ram = new int[0x10000];
    private final Mailbox mailbox = new Mailbox();
    private final Z80 cpu;
    private final long z80ClockHz;

    private boolean bootMode;

    /** Bytes the current instruction attempt took from the mailbox, for replay after a stall. */
    private final int[] taken = new int[8];
    private int takenCount;
    private int replayIndex;

    /** Z80 time owed, scaled so a host cycle adds z80ClockHz x 912 and a T-state costs 14,318,180 x 65. */
    private long owed;
    /** The mailbox load count when the CPU last stalled; it can't make progress until that changes. */
    private long loadsAtStall = -1;

    /** Builds a board with the default Z80 clock. */
    public SoftCardBoard() {
        this(DEFAULT_Z80_CLOCK_HZ);
    }

    /**
     * @param z80ClockHz the Z80's clock. The board has a 12 MHz oscillator
     *                   and a Z80B (rated 6 MHz), which suggests 6 MHz, but
     *                   the divider isn't confirmed; hence a parameter.
     */
    public SoftCardBoard(int z80ClockHz) {
        if (z80ClockHz <= 0) {
            throw new IllegalArgumentException("Z80 clock must be positive: " + z80ClockHz);
        }
        this.z80ClockHz = z80ClockHz;
        this.cpu = new Z80(new Bus());
        reset();
    }

    /**
     * Resets the Z80 and enters boot mode: the effect of the host's
     * RESET line and of a write to $C0n3.
     */
    public void reset() {
        cpu.reset();
        bootMode = true;
        takenCount = 0;
        replayIndex = 0;
        loadsAtStall = -1;
    }

    /**
     * The host reads its I/O space.
     *
     * @param offset 0-15 within $C0n0-$C0nF
     * @return the byte read
     */
    public int hostRead(int offset) {
        switch (offset) {
            case 0: return mailbox.hostRead();
            case 1: return mailbox.hostStatus();
            default: return 0; // undecoded as far as any known code shows
        }
    }

    /**
     * The host writes its I/O space.
     *
     * @param offset 0-15 within $C0n0-$C0nF
     * @param value the byte written
     */
    public void hostWrite(int offset, int value) {
        switch (offset) {
            case 0: mailbox.hostWrite(value); break;
            case 3: reset(); break;
            default: break; // $C0n1 is written by every send's ROR poll and must be harmless
        }
    }

    /**
     * Runs the Z80 for as long as the given amount of host time.
     *
     * @param hostCycles elapsed Apple II CPU cycles
     */
    public void advance(int hostCycles) {
        owed += hostCycles * z80ClockHz * HOST_CLOCK_DENOMINATOR;
        long perTState = HOST_CLOCK_NUMERATOR;
        while (owed >= perTState) {
            if (cpu.isStalled() && mailbox.toZ80Loads() == loadsAtStall) {
                owed %= perTState; // still waiting on the 6502: the time passes in /WAIT
                return;
            }
            int t = cpu.step();
            if (t == 0) {
                loadsAtStall = mailbox.toZ80Loads();
            } else {
                owed -= t * perTState;
            }
        }
    }

    /** @return true while the board is in boot mode */
    public boolean isBootMode() {
        return bootMode;
    }

    /** @return the Z80, for inspection by tests and debuggers */
    public Z80 cpu() {
        return cpu;
    }

    /**
     * Reads the board's RAM directly, bypassing the Z80.
     *
     * @param address 16-bit address
     * @return the byte
     */
    public int peekRam(int address) {
        return ram[address & 0xFFFF];
    }

    private final class Bus implements Z80Bus {

        @Override
        public void beginInstruction(boolean retry) {
            if (!retry) {
                takenCount = 0;
            }
            replayIndex = 0;
        }

        @Override
        public int read(int address) {
            if (bootMode && address < 0x8000) {
                if (replayIndex < takenCount) {
                    return taken[replayIndex++];
                }
                if (!mailbox.z80InputFull()) {
                    throw WaitState.STALL;
                }
                int v = mailbox.z80Read();
                taken[takenCount++] = v;
                replayIndex++;
                return v;
            }
            return ram[address];
        }

        @Override
        public void write(int address, int value) {
            if (bootMode && address < 0x8000) {
                mailbox.z80Write(value);
            } else {
                ram[address] = value & 0xFF;
            }
        }

        @Override
        public int in(int port) {
            return (port & 1) == 0 ? mailbox.z80Read() : mailbox.z80Status();
        }

        @Override
        public void out(int port, int value) {
            if (bootMode) {
                bootMode = false; // the first I/O write ends boot mode and latches nothing
            } else if ((port & 1) == 0) {
                mailbox.z80Write(value);
            }
        }
    }
}
