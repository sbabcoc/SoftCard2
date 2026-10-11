package com.nordstrom.softcard.z80;

/**
 * Thrown by a {@link Z80Bus} read to hold the CPU, as the /WAIT input does.
 * A single shared instance without a stack trace: a stall is ordinary
 * control flow on a polling device, not an error, and can happen on most
 * steps while one processor waits on the other.
 */
public final class WaitState extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The one instance; throw this. */
    public static final WaitState STALL = new WaitState();

    private WaitState() {
        super("Z80 /WAIT asserted", null, false, false);
    }
}
