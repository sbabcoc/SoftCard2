package com.nordstrom.softcard.slot;

import com.nordstrom.emulator.MemoryBus;
import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.expansion.LanguageCard;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.transfer.HostTransferCard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalInt;
import java.util.Properties;

/**
 * Just enough Apple II+ to boot the SoftCard II CP/M disk: ReplicApple2Plus's
 * own 6502, Language Card (slot 0), host file transfer card (slot 2) and
 * Disk II controller (slot 6), this project's card in slot 4, 48K of RAM and
 * a keyboard. Slot 2 is CP/M's reader and punch slot, where the transfer
 * card serves CP/M as a serial card.
 * <p>
 * The Apple II+ ROMs aren't part of either project, so the motherboard ROM
 * here is a stand-in: every byte is RTS, except that the reset vector points
 * at the Disk II boot ROM ($C600), $FF65 (the Monitor entry the loader jumps
 * to on a wrong-slot boot) loops in place, and $FCA8 holds the Monitor's
 * WAIT delay loop. That is all the CP/M disk needs: its loader calls only
 * Monitor routines whose effects (screen and keyboard setup, a bell) don't
 * matter to the boot, and the Disk II boot ROM needs only an RTS at $FF58
 * and a real delay at $FCA8.
 * <p>
 * Empty slots and the expansion window read as a varying floating bus, so
 * the loader's double checksum of each slot ROM page comes out unstable,
 * which is how it recognizes an empty slot.
 */
final class CpmMachine {

    static final int CARD_SLOT = 4;
    static final int TRANSFER_SLOT = 2;

    /**
     * The Monitor's WAIT delay loop at $FCA8, which the Disk II boot ROM
     * calls between head steps. Its timing matters: Disk2Controller models
     * the stepper, and a head stepped faster than this never reaches track 0.
     */
    private static final int[] WAIT = {
        0x38,             // SEC
        0x48,             // PHA
        0xE9, 0x01,       // SBC #1
        0xD0, 0xFC,       // BNE inner
        0x68,             // PLA
        0xE9, 0x01,       // SBC #1
        0xD0, 0xF6,       // BNE outer
        0x60              // RTS
    };

    private final int[] ram = new int[0xC000];
    private final SlotCard[] slots = new SlotCard[8];
    private final LanguageCard languageCard = new LanguageCard();
    private final Disk2Controller disk = new Disk2Controller();
    private final SoftCard2 card = new SoftCard2();
    private final HostTransferCard transferCard = new HostTransferCard();
    private final Cpu6502 cpu;
    private final Deque<Integer> keys = new ArrayDeque<>();
    private final ConsoleTap console = new ConsoleTap();
    private int keyLatch;
    private int floating = 0x5A;
    private long cycles;

    CpmMachine(Path bootDisk) throws IOException {
        languageCard.configure(new Properties());
        Properties diskProps = new Properties();
        diskProps.setProperty("drive1", bootDisk.toString());
        disk.configure(diskProps);
        disk.setFloatingBusSupplier(this::floatingBus);
        card.configure(new Properties());
        slots[0] = languageCard;
        slots[6] = disk;
        slots[CARD_SLOT] = card;
        slots[TRANSFER_SLOT] = transferCard;
        cpu = new Cpu6502(new Bus(), 0xFFFC);
    }

    SoftCard2 card() {
        return card;
    }

    HostTransferCard transferCard() {
        return transferCard;
    }

    long cycles() {
        return cycles;
    }

    /** @return everything the Z80 has sent to the console so far */
    String console() {
        return console.text();
    }

    /** Runs the machine for at least the given number of 6502 cycles. */
    void run(long budget) {
        long end = cycles + budget;
        while (cycles < end) {
            int c = cpu.step();
            if (c == 0) {
                throw new IllegalStateException("6502 halted");
            }
            cycles += c;
            disk.tick(c);
            card.tick(c);
        }
    }

    /** Queues keystrokes, one per keyboard strobe. */
    void type(String text) {
        for (char ch : text.toCharArray()) {
            keys.add(ch == '\n' ? 0x0D : (int) Character.toUpperCase(ch));
        }
    }

    /** @return the 40-column text page, one line per screen row, trailing blanks trimmed */
    String screen() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 24; row++) {
            int base = 0x400 + (row % 8) * 0x80 + (row / 8) * 0x28;
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < 40; col++) {
                int c = ram[base + col] & 0x7F;
                line.append((char) (c < 0x20 ? c + 0x40 : c));
            }
            sb.append(line.toString().stripTrailing()).append('\n');
        }
        return sb.toString();
    }

    private int floatingBus() {
        floating = (floating * 109 + 89) & 0xFF;
        return floating;
    }

    private final class Bus implements MemoryBus {

        @Override
        public int read(int address) {
            if (address < 0xC000) {
                return ram[address];
            }
            if (address < 0xC100) {
                return readIo(address);
            }
            if (address < 0xC800) {
                SlotCard slotCard = slots[(address >> 8) & 7];
                return slotCard != null && slotCard.hasRom() ? slotCard.readRom(address & 0xFF) : floatingBus();
            }
            if (address < 0xD000) {
                return floatingBus();
            }
            OptionalInt fromCard = languageCard.readUpperMemory(address - 0xD000);
            return fromCard.isPresent() ? fromCard.getAsInt() : standInRom(address);
        }

        @Override
        public void write(int address, int value) {
            if (address < 0xC000) {
                ram[address] = value & 0xFF;
            } else if (address < 0xC100) {
                writeIo(address, value);
            } else if (address >= 0xD000) {
                languageCard.writeUpperMemory(address - 0xD000, value);
            }
        }

        private int readIo(int address) {
            if (address < 0xC010) {
                if (keyLatch < 0x80 && !keys.isEmpty()) {
                    keyLatch = keys.removeFirst() | 0x80;
                }
                return keyLatch;
            }
            if (address < 0xC020) {
                keyLatch &= 0x7F;
                return floatingBus();
            }
            if (address >= 0xC080) {
                SlotCard slotCard = slots[(address >> 4) & 7];
                int value = slotCard != null ? slotCard.readIoSwitch(address & 0x0F) : floatingBus();
                if (address == 0xC080 + CARD_SLOT * 16 && !card.board().isBootMode()) {
                    console.accept(value); // reads during the probe and bootstrap aren't protocol traffic
                }
                return value;
            }
            return floatingBus();
        }

        private void writeIo(int address, int value) {
            if (address >= 0xC010 && address < 0xC020) {
                keyLatch &= 0x7F;
            } else if (address >= 0xC080) {
                SlotCard slotCard = slots[(address >> 4) & 7];
                if (slotCard != null) {
                    slotCard.writeIoSwitch(address & 0x0F, value);
                }
            }
        }

        private int standInRom(int address) {
            if (address >= 0xFCA8 && address < 0xFCA8 + WAIT.length) {
                return WAIT[address - 0xFCA8];
            }
            switch (address) {
                case 0xFFFC: return 0x00;
                case 0xFFFD: return 0xC6;
                case 0xFF65: return 0x4C; // JMP $FF65
                case 0xFF66: return 0x65;
                case 0xFF67: return 0xFF;
                default: return 0x60; // RTS
            }
        }
    }
}
