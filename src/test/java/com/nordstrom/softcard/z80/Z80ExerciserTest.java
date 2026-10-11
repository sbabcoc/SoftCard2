package com.nordstrom.softcard.z80;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frank Cringle's Z80 instruction exercisers, run unmodified. Each test
 * executes a family of instructions over thousands of operand and flag
 * combinations and compares a CRC of the results with one recorded from a
 * real Z80. zexdoc checks the documented flags; zexall checks all eight.
 */
class Z80ExerciserTest {

    /** zexdoc and zexall each finish in about 47 billion T-states. */
    private static final long MAX_T_STATES = 50_000_000_000L;

    @Test
    void zexdocPassesEveryTest() throws IOException {
        String output = CpmExerciser.run("/zexdoc.com", MAX_T_STATES);
        assertTrue(output.contains("Tests complete"), output);
        assertEquals(List.of(), failures(output), output);
    }

    /**
     * The one expected failure: after BIT n,(HL) a real Z80 copies bits 3
     * and 5 of its internal MEMPTR latch into the flags, and {@link Z80}
     * doesn't model MEMPTR (see its class Javadoc).
     */
    @Test
    void zexallPassesEveryTestExceptTheMemptrDependentBit() throws IOException {
        String output = CpmExerciser.run("/zexall.com", MAX_T_STATES);
        assertTrue(output.contains("Tests complete"), output);
        assertEquals(List.of("bit n,<b,c,d,e,h,l,(hl),a>"), failures(output), output);
    }

    private static List<String> failures(String output) {
        return output.lines()
                .map(String::strip)
                .filter(line -> line.contains("ERROR"))
                .map(line -> line.substring(0, line.indexOf("..")))
                .collect(Collectors.toList());
    }
}
