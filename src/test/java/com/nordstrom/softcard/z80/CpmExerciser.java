package com.nordstrom.softcard.z80;

import java.io.IOException;
import java.io.InputStream;

/**
 * Runs a CP/M .COM program on a bare {@link Z80} with just enough of CP/M
 * for the instruction exercisers: the program loads at $0100, BDOS calls
 * at $0005 handle console output (functions 2 and 9), and a jump to $0000
 * ends the run.
 */
final class CpmExerciser {

    private static final int BDOS = 0x0005;
    private static final int STACK_TOP = 0xFE00;

    private CpmExerciser() {
    }

    /**
     * @param resource classpath resource name of the .COM file
     * @param maxTStates a bound that stops a runaway program
     * @return everything the program printed
     */
    static String run(String resource, long maxTStates) throws IOException {
        int[] memory = new int[0x10000];
        try (InputStream in = CpmExerciser.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Test resource " + resource + " not found on the classpath");
            }
            byte[] program = in.readAllBytes();
            for (int i = 0; i < program.length; i++) {
                memory[0x100 + i] = program[i] & 0xFF;
            }
        }
        memory[BDOS] = 0xC3; // JP STACK_TOP: the exercisers take their stack from the BDOS jump's operand
        memory[BDOS + 1] = STACK_TOP & 0xFF;
        memory[BDOS + 2] = STACK_TOP >> 8;

        Z80 cpu = new Z80(new Z80Bus() {
            @Override
            public int read(int address) {
                return memory[address];
            }

            @Override
            public void write(int address, int value) {
                memory[address] = value & 0xFF;
            }

            @Override
            public int in(int port) {
                return 0xFF;
            }

            @Override
            public void out(int port, int value) {
                // no devices
            }
        });
        cpu.setPc(0x100);

        StringBuilder output = new StringBuilder();
        long tStates = 0;
        while (cpu.getPc() != 0) {
            if (cpu.getPc() == BDOS) {
                int function = cpu.getBc() & 0xFF;
                if (function == 2) {
                    output.append((char) (cpu.getDe() & 0x7F));
                } else if (function == 9) {
                    for (int address = cpu.getDe(); memory[address] != '$'; address = (address + 1) & 0xFFFF) {
                        output.append((char) (memory[address] & 0x7F));
                    }
                }
                cpu.ret();
                continue;
            }
            tStates += cpu.step();
            if (tStates > maxTStates) {
                throw new IllegalStateException("Exceeded " + maxTStates + " T-states; output so far:\n" + output);
            }
        }
        return output.toString();
    }
}
