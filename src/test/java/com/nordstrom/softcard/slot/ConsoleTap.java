package com.nordstrom.softcard.slot;

/**
 * Recovers the CP/M console stream from the bytes the 6502 reads out of the
 * SoftCard II's mailbox, using the command protocol in HARDWARE-REFERENCE.md.
 * <p>
 * This doubles as a check on that protocol: it knows only how many bytes
 * each command carries from the Z80 to the 6502, so a wrong count anywhere
 * in the table desynchronizes it, and the recovered text turns to garbage
 * or an unknown command byte stops it outright.
 */
final class ConsoleTap {

    /** Bytes each command carries from the Z80 to the 6502 after the command byte, by command number. */
    private static final int[] PARAMETER_BYTES = {
        5,          // 0  call 6502 subroutine: address, A, X, Y
        2,          // 1  read 6502 memory: address
        3,          // 2  write 6502 memory: address, value
        4,          // 3  read sector: track, drive, slot, sector
        4 + 256,    // 4  write sector: track, drive, slot, sector, data
        1,          // 5  character input: slot
        2,          // 6  character output: slot, character
        1,          // 7  character status: slot
        1,          // 8  character device init: slot
        0,          // 9  warm boot
        2,          // 10 format: slot, drive
        34,         // 11 terminal definition
        2,          // 12 beep
        2,          // 13 fill hi-res page 1
        0,          // 14 invert hi-res page 1
        4,          // 15 masked write: address, value, mask
        3           // 16 XOR write: address, mask
    };

    private static final int CONSOLE_SLOT = 3;

    private final StringBuilder text = new StringBuilder();
    private int command = -1;
    private int position;
    private int slot;

    void accept(int value) {
        if (command < 0) {
            if (value >= PARAMETER_BYTES.length) {
                throw new IllegalStateException(String.format("Unknown SoftCard II command $%02X", value));
            }
            command = value;
            position = 0;
        } else {
            if (command == 6) {
                if (position == 0) {
                    slot = value;
                } else if (slot == CONSOLE_SLOT) {
                    text.append((char) (value & 0x7F));
                }
            }
            position++;
        }
        if (position == PARAMETER_BYTES[command]) {
            command = -1;
        }
    }

    String text() {
        return text.toString();
    }
}
