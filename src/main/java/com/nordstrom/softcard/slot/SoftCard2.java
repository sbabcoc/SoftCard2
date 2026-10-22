package com.nordstrom.softcard.slot;

import com.nordstrom.emulator.system.CardParameter;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.softcard.board.SoftCardBoard;

import java.util.List;
import java.util.Properties;

/**
 * ReplicApple2Plus peripheral wrapping a {@link SoftCardBoard}. The card
 * occupies only its $C0n0-$C0nF I/O space: it has no ROM, so its $Cn00 page
 * reads as the floating bus, which is exactly what the CP/M loader's slot
 * scan expects before it probes a slot for this card.
 * <p>
 * The Z80 runs on its own clock, kept in step with the 6502 through
 * {@link #tick}.
 */
public final class SoftCard2 implements SlotCard {

    private static final String Z80_CLOCK_HZ = "z80ClockHz";

    private SoftCardBoard board;

    @Override
    public String getShortName() {
        return "softcard2";
    }

    @Override
    public String getDisplayName() {
        return "Microsoft SoftCard II";
    }

    /** The Z80's clock: any positive rate {@link SoftCardBoard} accepts, its default unless set. */
    @Override
    public List<CardParameter> getParameters() {
        return List.of(CardParameter.integer(Z80_CLOCK_HZ, "Z80 clock (Hz)",
            SoftCardBoard.DEFAULT_Z80_CLOCK_HZ, 1, Integer.MAX_VALUE));
    }

    @Override
    public void configure(Properties props) {
        String clock = props.getProperty(Z80_CLOCK_HZ);
        board = clock == null ? new SoftCardBoard() : new SoftCardBoard(Integer.parseInt(clock.trim()));
    }

    @Override
    public int readIoSwitch(int offset) {
        return board.hostRead(offset);
    }

    @Override
    public void writeIoSwitch(int offset, int value) {
        board.hostWrite(offset, value);
    }

    @Override
    public void onReset() {
        board.reset();
    }

    @Override
    public boolean hasRom() {
        return false;
    }

    @Override
    public int readRom(int offset) {
        throw new UnsupportedOperationException("The SoftCard II has no slot ROM");
    }

    /**
     * Runs the Z80 for the given amount of host time.
     *
     * @param cycles elapsed 6502 cycles
     */
    @Override
    public void tick(int cycles) {
        board.advance(cycles);
    }

    /** @return the board, for inspection by tests and debuggers */
    public SoftCardBoard board() {
        return board;
    }
}
