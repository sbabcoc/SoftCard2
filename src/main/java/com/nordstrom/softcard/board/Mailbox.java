package com.nordstrom.softcard.board;

/**
 * The SoftCard II's processor-to-processor mailbox, an AMD Am2950
 * bidirectional I/O port (U20 on the board): two back-to-back 8-bit
 * registers, one per direction, each with a flag flip-flop that sets when
 * the register is loaded and clears when it is read.
 * <p>
 * Each side sees the same two facts through its own status read, from its
 * own point of view: bit 7 is "a byte is waiting for me" and bit 0 is "the
 * byte I sent hasn't been taken yet". The 6502 code polls exactly those
 * bits before every transfer, and so does the Z80 BIOS. Which pins carry
 * the flags onto each data bus isn't visible in the available photos; the
 * bit positions come from the code that polls them.
 */
public final class Mailbox {

    /** Status bit: a byte is waiting to be read by this side. */
    public static final int INPUT_FULL = 0x80;
    /** Status bit: this side's last byte hasn't been read by the other side. */
    public static final int OUTPUT_FULL = 0x01;

    private int toZ80;
    private boolean toZ80Full;
    private int toHost;
    private boolean toHostFull;
    /** Counts loads of the 6502-to-Z80 register; a stalled Z80 only retries once it changes. */
    private long toZ80Loads;

    /**
     * The 6502 writes $C0n0.
     *
     * @param value byte for the Z80
     */
    public void hostWrite(int value) {
        toZ80 = value & 0xFF;
        toZ80Full = true;
        toZ80Loads++;
    }

    /**
     * The 6502 reads $C0n0.
     *
     * @return the Z80's last byte (stale if none is waiting, as a latch is)
     */
    public int hostRead() {
        toHostFull = false;
        return toHost;
    }

    /** @return the 6502's view of the status bits */
    public int hostStatus() {
        return (toHostFull ? INPUT_FULL : 0) | (toZ80Full ? OUTPUT_FULL : 0);
    }

    /**
     * The Z80 loads its outgoing register.
     *
     * @param value byte for the 6502
     */
    void z80Write(int value) {
        toHost = value & 0xFF;
        toHostFull = true;
    }

    /** @return true if a byte from the 6502 is waiting */
    boolean z80InputFull() {
        return toZ80Full;
    }

    /** @return the 6502's last byte, clearing its flag */
    int z80Read() {
        toZ80Full = false;
        return toZ80;
    }

    /** @return the Z80's view of the status bits */
    int z80Status() {
        return (toZ80Full ? INPUT_FULL : 0) | (toHostFull ? OUTPUT_FULL : 0);
    }

    /** @return how many times the 6502 has loaded a byte for the Z80 */
    long toZ80Loads() {
        return toZ80Loads;
    }
}
