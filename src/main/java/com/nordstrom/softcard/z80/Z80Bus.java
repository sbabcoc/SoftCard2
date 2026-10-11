package com.nordstrom.softcard.z80;

/**
 * Everything a {@link Z80} can reach: memory and the I/O port space.
 * <p>
 * A read may decline to complete by throwing {@link WaitState#STALL}. That
 * models the Z80's /WAIT input: real hardware holds the CPU mid-cycle until
 * the addressed device is ready. {@link Z80#step} answers a stall by
 * restoring the register state it held at the start of the instruction and
 * retrying the whole instruction later, so a bus that stalls must be able to
 * replay, unchanged, any reads that instruction already completed. See
 * {@link #beginInstruction}.
 */
public interface Z80Bus {

    /**
     * Reads a byte of memory.
     *
     * @param address 16-bit address
     * @return the byte, 0-255
     * @throws WaitState if the device isn't ready; the instruction will be retried
     */
    int read(int address);

    /**
     * Writes a byte of memory.
     *
     * @param address 16-bit address
     * @param value byte to write; only the low 8 bits matter
     */
    void write(int address, int value);

    /**
     * Reads an I/O port. The Z80 drives the full 16-bit address bus during
     * I/O cycles (the upper byte is A or B, depending on the instruction);
     * which bits a device decodes is the device's business.
     *
     * @param port 16-bit port address
     * @return the byte, 0-255
     * @throws WaitState if the device isn't ready; the instruction will be retried
     */
    int in(int port);

    /**
     * Writes an I/O port.
     *
     * @param port 16-bit port address
     * @param value byte to write; only the low 8 bits matter
     */
    void out(int port, int value);

    /**
     * Called before the first bus cycle of every instruction attempt,
     * including a retry after a stall. A bus that never stalls can ignore it.
     *
     * @param retry true if the previous attempt at this same instruction stalled
     */
    default void beginInstruction(boolean retry) {
        // nothing to track for a bus that never stalls
    }
}
