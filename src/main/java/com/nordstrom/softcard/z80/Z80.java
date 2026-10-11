package com.nordstrom.softcard.z80;

/**
 * A Zilog Z80 CPU, executed one whole instruction at a time with exact
 * T-state counts.
 * <p>
 * Decoding follows the opcode map's own structure rather than a 256-way
 * table of handlers: each opcode splits into fields {@code x} (bits 7-6),
 * {@code y} (bits 5-3), {@code z} (bits 2-0), and {@code y}'s halves
 * {@code p} (bits 5-4) and {@code q} (bit 3). Within a quadrant, {@code y}
 * and {@code z} index the same register, register-pair, condition and ALU
 * tables the silicon's decoder does, so one rule covers each family of
 * instructions instead of one handler per opcode.
 * <p>
 * The DD and FD prefixes are modeled the way the chip handles them: they
 * set which 16-bit register stands in for HL in the instruction that
 * follows. Where that instruction names H or L, the prefix makes it the
 * corresponding half of IX or IY (the undocumented IXH/IXL forms fall out
 * of this for free); where it names (HL), the prefix makes it (IX+d) and
 * any H or L operand in the same instruction stays H or L.
 * <p>
 * The undocumented flag bits 3 and 5 (here {@link #XF} and {@link #YF}) are
 * produced as documented by the instruction-set exercisers' reference
 * behavior. MEMPTR (the internal address latch) isn't modeled: its only
 * software-visible effect is on bits 3 and 5 after {@code BIT n,(HL)},
 * which is outside what anything run on this board is known to observe.
 * <p>
 * /INT and /NMI acceptance isn't modeled. Nothing on the SoftCard II board
 * is known to drive either input; EI, DI and IM still execute and maintain
 * IFF1, IFF2 and the interrupt mode, so software that sets them up behaves.
 */
public final class Z80 {

    /** Carry flag. */
    public static final int CF = 0x01;
    /** Add/subtract flag. */
    public static final int NF = 0x02;
    /** Parity/overflow flag. */
    public static final int PF = 0x04;
    /** Undocumented flag, bit 3. */
    public static final int XF = 0x08;
    /** Half-carry flag. */
    public static final int HF = 0x10;
    /** Undocumented flag, bit 5. */
    public static final int YF = 0x20;
    /** Zero flag. */
    public static final int ZF = 0x40;
    /** Sign flag. */
    public static final int SF = 0x80;

    /** S, Z, Y and X for every byte value. */
    private static final int[] SZ = new int[256];
    /** S, Z, Y, X and even parity for every byte value. */
    private static final int[] SZP = new int[256];

    /** Interrupt mode selected by ED-prefixed IM opcodes, indexed by {@code y}. */
    private static final int[] IM_MODE = {0, 0, 1, 2, 0, 0, 1, 2};

    static {
        for (int v = 0; v < 256; v++) {
            SZ[v] = (v & (SF | YF | XF)) | (v == 0 ? ZF : 0);
            SZP[v] = SZ[v] | ((Integer.bitCount(v) & 1) == 0 ? PF : 0);
        }
    }

    private final Z80Bus bus;

    private int a, f, b, c, d, e, h, l;
    private int af2, bc2, de2, hl2;
    private int ix, iy, sp, pc, i, r;
    private boolean iff1, iff2;
    private int im;
    private boolean halted;

    /** Which register stands in for HL in this instruction: 0 HL, 1 IX, 2 IY. */
    private int idx;
    /** T-states consumed by the instruction being executed. */
    private int t;

    private final int[] snapshot = new int[22];
    private boolean stalled;

    /**
     * @param bus memory and I/O
     */
    public Z80(Z80Bus bus) {
        this.bus = bus;
        reset();
    }

    /**
     * Asserts /RESET: clears PC, I, R, the interrupt flip-flops and mode,
     * and leaves HALT. Other registers keep their contents, as on the chip.
     */
    public void reset() {
        pc = 0;
        i = 0;
        r = 0;
        iff1 = false;
        iff2 = false;
        im = 0;
        halted = false;
        stalled = false;
        sp = 0xFFFF;
        a = 0xFF;
        f = 0xFF;
    }

    /**
     * Executes one instruction.
     *
     * @return T-states consumed, or 0 if the bus stalled the instruction;
     *         in that case the CPU's state is exactly as it was before the
     *         call and {@link #isStalled()} is true until a retry completes
     */
    public int step() {
        boolean retry = stalled;
        save();
        bus.beginInstruction(retry);
        t = 0;
        try {
            if (halted) {
                incrementR();
                t = 4;
            } else {
                execute();
            }
            stalled = false;
            return t;
        } catch (WaitState wait) {
            restore();
            stalled = true;
            return 0;
        }
    }

    /** @return true if the last {@link #step} was held off by /WAIT */
    public boolean isStalled() {
        return stalled;
    }

    /** @return true if a HALT instruction is holding the CPU */
    public boolean isHalted() {
        return halted;
    }

    /** @return the program counter */
    public int getPc() {
        return pc;
    }

    /**
     * @param value the new program counter
     */
    public void setPc(int value) {
        pc = value & 0xFFFF;
    }

    /** @return the stack pointer */
    public int getSp() {
        return sp;
    }

    /**
     * @param value the new stack pointer
     */
    public void setSp(int value) {
        sp = value & 0xFFFF;
    }

    /** @return A */
    public int getA() {
        return a;
    }

    /** @return F */
    public int getF() {
        return f;
    }

    /** @return BC */
    public int getBc() {
        return (b << 8) | c;
    }

    /** @return DE */
    public int getDe() {
        return (d << 8) | e;
    }

    /** @return HL */
    public int getHl() {
        return (h << 8) | l;
    }

    /** @return IX */
    public int getIx() {
        return ix;
    }

    /** @return IY */
    public int getIy() {
        return iy;
    }

    /**
     * Executes a RET: pops the program counter. For harnesses that service a
     * CALL themselves (a CP/M BDOS stand-in, say) and need to return from it.
     */
    public void ret() {
        pc = pop();
    }

    // ------------------------------------------------------------------
    // rollback for /WAIT

    private void save() {
        int[] s = snapshot;
        s[0] = a; s[1] = f; s[2] = b; s[3] = c; s[4] = d; s[5] = e; s[6] = h; s[7] = l;
        s[8] = af2; s[9] = bc2; s[10] = de2; s[11] = hl2;
        s[12] = ix; s[13] = iy; s[14] = sp; s[15] = pc; s[16] = i; s[17] = r;
        s[18] = iff1 ? 1 : 0; s[19] = iff2 ? 1 : 0; s[20] = im; s[21] = halted ? 1 : 0;
    }

    private void restore() {
        int[] s = snapshot;
        a = s[0]; f = s[1]; b = s[2]; c = s[3]; d = s[4]; e = s[5]; h = s[6]; l = s[7];
        af2 = s[8]; bc2 = s[9]; de2 = s[10]; hl2 = s[11];
        ix = s[12]; iy = s[13]; sp = s[14]; pc = s[15]; i = s[16]; r = s[17];
        iff1 = s[18] != 0; iff2 = s[19] != 0; im = s[20]; halted = s[21] != 0;
    }

    // ------------------------------------------------------------------
    // bus cycles

    /** An opcode fetch (M1 cycle): advances the refresh counter's low 7 bits. */
    private int fetchOpcode() {
        incrementR();
        return fetch8();
    }

    private void incrementR() {
        r = (r & 0x80) | ((r + 1) & 0x7F);
    }

    private int fetch8() {
        int v = bus.read(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private int fetch16() {
        int lo = fetch8();
        return lo | (fetch8() << 8);
    }

    private int read16(int address) {
        int lo = bus.read(address);
        return lo | (bus.read((address + 1) & 0xFFFF) << 8);
    }

    private void write16(int address, int value) {
        bus.write(address, value & 0xFF);
        bus.write((address + 1) & 0xFFFF, (value >> 8) & 0xFF);
    }

    private void push(int value) {
        sp = (sp - 1) & 0xFFFF;
        bus.write(sp, (value >> 8) & 0xFF);
        sp = (sp - 1) & 0xFFFF;
        bus.write(sp, value & 0xFF);
    }

    private int pop() {
        int value = read16(sp);
        sp = (sp + 2) & 0xFFFF;
        return value;
    }

    // ------------------------------------------------------------------
    // register tables

    private int hl() {
        return (h << 8) | l;
    }

    private void setHl(int v) {
        h = (v >> 8) & 0xFF;
        l = v & 0xFF;
    }

    /** HL, or IX/IY under a prefix. */
    private int hlx() {
        return idx == 0 ? hl() : idx == 1 ? ix : iy;
    }

    private void setHlx(int v) {
        v &= 0xFFFF;
        if (idx == 0) {
            setHl(v);
        } else if (idx == 1) {
            ix = v;
        } else {
            iy = v;
        }
    }

    /** {@code rp[p]}: BC, DE, HL (or IX/IY), SP. */
    private int getRp(int p) {
        switch (p) {
            case 0: return (b << 8) | c;
            case 1: return (d << 8) | e;
            case 2: return hlx();
            default: return sp;
        }
    }

    private void setRp(int p, int v) {
        v &= 0xFFFF;
        switch (p) {
            case 0: b = v >> 8; c = v & 0xFF; break;
            case 1: d = v >> 8; e = v & 0xFF; break;
            case 2: setHlx(v); break;
            default: sp = v; break;
        }
    }

    /** {@code rp2[p]}: as {@link #getRp} but AF in place of SP. */
    private int getRp2(int p) {
        return p == 3 ? (a << 8) | f : getRp(p);
    }

    private void setRp2(int p, int v) {
        if (p == 3) {
            a = (v >> 8) & 0xFF;
            f = v & 0xFF;
        } else {
            setRp(p, v);
        }
    }

    /** {@code r[y]} for y != 6, with H and L following the prefix. */
    private int getR(int y) {
        switch (y) {
            case 0: return b;
            case 1: return c;
            case 2: return d;
            case 3: return e;
            case 4: return idx == 0 ? h : idx == 1 ? ix >> 8 : iy >> 8;
            case 5: return idx == 0 ? l : idx == 1 ? ix & 0xFF : iy & 0xFF;
            default: return a;
        }
    }

    private void setR(int y, int v) {
        v &= 0xFF;
        switch (y) {
            case 0: b = v; break;
            case 1: c = v; break;
            case 2: d = v; break;
            case 3: e = v; break;
            case 4:
                if (idx == 0) {
                    h = v;
                } else if (idx == 1) {
                    ix = (v << 8) | (ix & 0xFF);
                } else {
                    iy = (v << 8) | (iy & 0xFF);
                }
                break;
            case 5:
                if (idx == 0) {
                    l = v;
                } else if (idx == 1) {
                    ix = (ix & 0xFF00) | v;
                } else {
                    iy = (iy & 0xFF00) | v;
                }
                break;
            default: a = v; break;
        }
    }

    /** {@code r[y]} for y != 6, ignoring the prefix (the other operand is (IX+d)). */
    private int getRPlain(int y) {
        int saved = idx;
        idx = 0;
        int v = getR(y);
        idx = saved;
        return v;
    }

    private void setRPlain(int y, int v) {
        int saved = idx;
        idx = 0;
        setR(y, v);
        idx = saved;
    }

    /**
     * The address (HL) names: HL itself, or IX/IY plus a displacement byte
     * fetched now. Charges the 8 T-states the displacement costs.
     */
    private int memAddress() {
        if (idx == 0) {
            return hl();
        }
        int disp = (byte) fetch8();
        t += 8;
        return (hlx() + disp) & 0xFFFF;
    }

    /** {@code r[y]} including y == 6, (HL) or (IX+d). */
    private int getOperand(int y) {
        if (y != 6) {
            return getR(y);
        }
        return bus.read(memAddress());
    }

    /** {@code cc[y]}: NZ, Z, NC, C, PO, PE, P, M. */
    private boolean condition(int y) {
        switch (y) {
            case 0: return (f & ZF) == 0;
            case 1: return (f & ZF) != 0;
            case 2: return (f & CF) == 0;
            case 3: return (f & CF) != 0;
            case 4: return (f & PF) == 0;
            case 5: return (f & PF) != 0;
            case 6: return (f & SF) == 0;
            default: return (f & SF) != 0;
        }
    }

    // ------------------------------------------------------------------
    // decode

    private void execute() {
        int op = fetchOpcode();
        idx = 0;
        while (op == 0xDD || op == 0xFD) {
            idx = op == 0xDD ? 1 : 2;
            t += 4;
            op = fetchOpcode();
        }
        if (op == 0xCB) {
            if (idx == 0) {
                executeCb();
            } else {
                executeIndexedCb();
            }
        } else if (op == 0xED) {
            idx = 0; // a DD/FD before ED has no effect on it
            executeEd();
        } else {
            executeMain(op);
        }
    }

    private void executeMain(int op) {
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;
        int p = y >> 1;
        int q = y & 1;
        switch (x) {
            case 0: quadrant0(y, z, p, q); break;
            case 1:
                if (y == 6 && z == 6) {
                    halted = true;
                    t += 4;
                } else if (y == 6) {
                    int v = getRPlain(z);
                    bus.write(memAddress(), v);
                    t += 7;
                } else if (z == 6) {
                    int v = bus.read(memAddress());
                    setRPlain(y, v);
                    t += 7;
                } else {
                    setR(y, getR(z));
                    t += 4;
                }
                break;
            case 2:
                t += z == 6 ? 7 : 4;
                alu(y, getOperand(z));
                break;
            default: quadrant3(y, z, p, q); break;
        }
    }

    private void quadrant0(int y, int z, int p, int q) {
        switch (z) {
            case 0:
                switch (y) {
                    case 0: t += 4; break;
                    case 1: {
                        int tmp = (a << 8) | f;
                        a = af2 >> 8;
                        f = af2 & 0xFF;
                        af2 = tmp;
                        t += 4;
                        break;
                    }
                    case 2: {
                        int disp = (byte) fetch8();
                        b = (b - 1) & 0xFF;
                        if (b != 0) {
                            pc = (pc + disp) & 0xFFFF;
                            t += 13;
                        } else {
                            t += 8;
                        }
                        break;
                    }
                    case 3: {
                        int disp = (byte) fetch8();
                        pc = (pc + disp) & 0xFFFF;
                        t += 12;
                        break;
                    }
                    default: {
                        int disp = (byte) fetch8();
                        if (condition(y - 4)) {
                            pc = (pc + disp) & 0xFFFF;
                            t += 12;
                        } else {
                            t += 7;
                        }
                        break;
                    }
                }
                break;
            case 1:
                if (q == 0) {
                    setRp(p, fetch16());
                    t += 10;
                } else {
                    addHl(getRp(p));
                    t += 11;
                }
                break;
            case 2:
                switch (y) {
                    case 0: bus.write(getRp(0), a); t += 7; break;
                    case 1: a = bus.read(getRp(0)); t += 7; break;
                    case 2: bus.write(getRp(1), a); t += 7; break;
                    case 3: a = bus.read(getRp(1)); t += 7; break;
                    case 4: write16(fetch16(), hlx()); t += 16; break;
                    case 5: setHlx(read16(fetch16())); t += 16; break;
                    case 6: bus.write(fetch16(), a); t += 13; break;
                    default: a = bus.read(fetch16()); t += 13; break;
                }
                break;
            case 3:
                setRp(p, getRp(p) + (q == 0 ? 1 : -1));
                t += 6;
                break;
            case 4:
            case 5:
                if (y == 6) {
                    int address = memAddress();
                    int v = bus.read(address);
                    bus.write(address, z == 4 ? inc8(v) : dec8(v));
                    t += 11;
                } else {
                    setR(y, z == 4 ? inc8(getR(y)) : dec8(getR(y)));
                    t += 4;
                }
                break;
            case 6:
                if (y == 6) {
                    int address = memAddress();
                    if (idx != 0) {
                        t -= 3; // the immediate byte's fetch overlaps the displacement add
                    }
                    bus.write(address, fetch8());
                    t += 10;
                } else {
                    setR(y, fetch8());
                    t += 7;
                }
                break;
            default:
                accumulatorOp(y);
                t += 4;
                break;
        }
    }

    private void quadrant3(int y, int z, int p, int q) {
        switch (z) {
            case 0:
                if (condition(y)) {
                    pc = pop();
                    t += 11;
                } else {
                    t += 5;
                }
                break;
            case 1:
                if (q == 0) {
                    setRp2(p, pop());
                    t += 10;
                } else {
                    switch (p) {
                        case 0: pc = pop(); t += 10; break;
                        case 1: {
                            int tmp = (b << 8) | c;
                            b = bc2 >> 8; c = bc2 & 0xFF; bc2 = tmp;
                            tmp = (d << 8) | e;
                            d = de2 >> 8; e = de2 & 0xFF; de2 = tmp;
                            tmp = hl();
                            setHl(hl2); hl2 = tmp;
                            t += 4;
                            break;
                        }
                        case 2: pc = hlx(); t += 4; break;
                        default: sp = hlx(); t += 6; break;
                    }
                }
                break;
            case 2: {
                int target = fetch16();
                if (condition(y)) {
                    pc = target;
                }
                t += 10;
                break;
            }
            case 3:
                switch (y) {
                    case 0: pc = fetch16(); t += 10; break;
                    case 2: {
                        int n = fetch8();
                        bus.out((a << 8) | n, a);
                        t += 11;
                        break;
                    }
                    case 3: {
                        int n = fetch8();
                        a = bus.in((a << 8) | n);
                        t += 11;
                        break;
                    }
                    case 4: {
                        int v = read16(sp);
                        write16(sp, hlx());
                        setHlx(v);
                        t += 19;
                        break;
                    }
                    case 5: {
                        int tmp = (d << 8) | e;
                        d = h; e = l;
                        setHl(tmp);
                        t += 4;
                        break;
                    }
                    case 6: iff1 = false; iff2 = false; t += 4; break;
                    case 7: iff1 = true; iff2 = true; t += 4; break;
                    default: throw new IllegalStateException("CB prefix reached the main decoder");
                }
                break;
            case 4: {
                int target = fetch16();
                if (condition(y)) {
                    push(pc);
                    pc = target;
                    t += 17;
                } else {
                    t += 10;
                }
                break;
            }
            case 5:
                if (q == 0) {
                    push(getRp2(p));
                    t += 11;
                } else {
                    // p == 0 is CALL nn; the DD, ED and FD prefixes never get here
                    int target = fetch16();
                    push(pc);
                    pc = target;
                    t += 17;
                }
                break;
            case 6:
                alu(y, fetch8());
                t += 7;
                break;
            default:
                push(pc);
                pc = y << 3;
                t += 11;
                break;
        }
    }

    // ------------------------------------------------------------------
    // CB prefix

    private void executeCb() {
        int op = fetchOpcode();
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;
        int v;
        int address = 0;
        if (z == 6) {
            address = hl();
            v = bus.read(address);
        } else {
            v = getR(z);
        }
        if (x == 1) {
            bit(y, v, v);
            t += z == 6 ? 12 : 8;
            return;
        }
        int result = x == 0 ? rotate(y, v) : x == 2 ? v & ~(1 << y) : v | (1 << y);
        if (z == 6) {
            bus.write(address, result);
            t += 15;
        } else {
            setR(z, result);
            t += 8;
        }
    }

    /** DD CB d op / FD CB d op: the displacement comes before the opcode, which is not an M1 fetch. */
    private void executeIndexedCb() {
        int address = (hlx() + (byte) fetch8()) & 0xFFFF;
        int op = fetch8();
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;
        int v = bus.read(address);
        if (x == 1) {
            bit(y, v, address >> 8);
            t += 16;
            return;
        }
        int result = x == 0 ? rotate(y, v) : x == 2 ? v & ~(1 << y) : v | (1 << y);
        bus.write(address, result);
        if (z != 6) {
            setRPlain(z, result); // undocumented: the result is also copied into r[z]
        }
        t += 19;
    }

    /** {@code rot[y]}: RLC, RRC, RL, RR, SLA, SRA, SLL (undocumented), SRL. */
    private int rotate(int y, int v) {
        int carry;
        int result;
        switch (y) {
            case 0: carry = v >> 7; result = (v << 1) | carry; break;
            case 1: carry = v & 1; result = (v >> 1) | (carry << 7); break;
            case 2: carry = v >> 7; result = (v << 1) | (f & CF); break;
            case 3: carry = v & 1; result = (v >> 1) | ((f & CF) << 7); break;
            case 4: carry = v >> 7; result = v << 1; break;
            case 5: carry = v & 1; result = (v >> 1) | (v & 0x80); break;
            case 6: carry = v >> 7; result = (v << 1) | 1; break;
            default: carry = v & 1; result = v >> 1; break;
        }
        result &= 0xFF;
        f = SZP[result] | carry;
        return result;
    }

    /**
     * BIT y,v.
     *
     * @param xy the value whose bits 3 and 5 surface in the flags: the
     *           operand for a register, the address high byte for (IX+d)
     */
    private void bit(int y, int v, int xy) {
        boolean set = (v & (1 << y)) != 0;
        f = (f & CF) | HF | (xy & (XF | YF))
                | (set ? (y == 7 ? SF : 0) : ZF | PF);
    }

    // ------------------------------------------------------------------
    // ED prefix

    private void executeEd() {
        int op = fetchOpcode();
        int x = op >> 6;
        int y = (op >> 3) & 7;
        int z = op & 7;
        int p = y >> 1;
        int q = y & 1;
        if (x == 1) {
            edQuadrant1(y, z, p, q);
        } else if (x == 2 && z <= 3 && y >= 4) {
            blockOp(y, z);
        } else {
            t += 8; // undefined ED opcodes behave as two NOPs
        }
    }

    private void edQuadrant1(int y, int z, int p, int q) {
        switch (z) {
            case 0: {
                int v = bus.in((b << 8) | c);
                if (y != 6) {
                    setR(y, v);
                }
                f = (f & CF) | SZP[v];
                t += 12;
                break;
            }
            case 1:
                bus.out((b << 8) | c, y == 6 ? 0 : getR(y));
                t += 12;
                break;
            case 2:
                if (q == 0) {
                    sbcHl(getRp(p));
                } else {
                    adcHl(getRp(p));
                }
                t += 15;
                break;
            case 3:
                if (q == 0) {
                    write16(fetch16(), getRp(p));
                } else {
                    setRp(p, read16(fetch16()));
                }
                t += 20;
                break;
            case 4: {
                int v = a;
                a = 0;
                alu(2, v);
                t += 8;
                break;
            }
            case 5:
                pc = pop();
                iff1 = iff2;
                t += 14;
                break;
            case 6:
                im = IM_MODE[y];
                t += 8;
                break;
            default:
                switch (y) {
                    case 0: i = a; t += 9; break;
                    case 1: r = a; t += 9; break;
                    case 2: a = i; f = (f & CF) | SZ[a] | (iff2 ? PF : 0); t += 9; break;
                    case 3: a = r; f = (f & CF) | SZ[a] | (iff2 ? PF : 0); t += 9; break;
                    case 4: {
                        int address = hl();
                        int m = bus.read(address);
                        bus.write(address, ((a << 4) | (m >> 4)) & 0xFF);
                        a = (a & 0xF0) | (m & 0x0F);
                        f = (f & CF) | SZP[a];
                        t += 18;
                        break;
                    }
                    case 5: {
                        int address = hl();
                        int m = bus.read(address);
                        bus.write(address, ((m << 4) | (a & 0x0F)) & 0xFF);
                        a = (a & 0xF0) | (m >> 4);
                        f = (f & CF) | SZP[a];
                        t += 18;
                        break;
                    }
                    default: t += 8; break;
                }
                break;
        }
    }

    /**
     * LDI/LDD/CPI/CPD/INI/IND/OUTI/OUTD (y 4, 5) and their repeating forms
     * (y 6, 7). A repeating form that isn't finished rewinds PC onto itself,
     * so each iteration is a separate instruction, as on the chip.
     */
    private void blockOp(int y, int z) {
        int step = (y & 1) == 0 ? 1 : -1;
        boolean repeat = y >= 6;
        int bc = (b << 8) | c;
        boolean again;
        switch (z) {
            case 0: {
                int v = bus.read(hl());
                bus.write((d << 8) | e, v);
                setHl(hl() + step);
                int de = ((d << 8) | e) + step;
                d = (de >> 8) & 0xFF; e = de & 0xFF;
                bc = (bc - 1) & 0xFFFF;
                b = bc >> 8; c = bc & 0xFF;
                int n = v + a;
                f = (f & (SF | ZF | CF)) | (bc != 0 ? PF : 0) | (n & XF) | ((n << 4) & YF);
                again = repeat && bc != 0;
                break;
            }
            case 1: {
                int v = bus.read(hl());
                int result = (a - v) & 0xFF;
                int half = (a ^ v ^ result) & HF;
                setHl(hl() + step);
                bc = (bc - 1) & 0xFFFF;
                b = bc >> 8; c = bc & 0xFF;
                int n = result - (half != 0 ? 1 : 0);
                f = (f & CF) | NF | (SZ[result] & (SF | ZF)) | half | (bc != 0 ? PF : 0)
                        | (n & XF) | ((n << 4) & YF);
                again = repeat && bc != 0 && result != 0;
                break;
            }
            case 2: {
                int v = bus.in(bc);
                bus.write(hl(), v);
                setHl(hl() + step);
                b = (b - 1) & 0xFF;
                blockIoFlags(v, (c + step) & 0xFF);
                again = repeat && b != 0;
                break;
            }
            default: {
                int v = bus.read(hl());
                b = (b - 1) & 0xFF;
                bus.out((b << 8) | c, v);
                setHl(hl() + step);
                blockIoFlags(v, l);
                again = repeat && b != 0;
                break;
            }
        }
        if (again) {
            pc = (pc - 2) & 0xFFFF;
            t += 21;
        } else {
            t += 16;
        }
    }

    private void blockIoFlags(int v, int other) {
        int k = v + other;
        int carry = k > 0xFF ? HF | CF : 0;
        f = SZ[b] | ((v & 0x80) != 0 ? NF : 0) | carry | (SZP[(k & 7) ^ b] & PF);
    }

    // ------------------------------------------------------------------
    // arithmetic

    /** {@code alu[y]}: ADD, ADC, SUB, SBC, AND, XOR, OR, CP. */
    private void alu(int y, int v) {
        switch (y) {
            case 0: add8(v, 0); break;
            case 1: add8(v, f & CF); break;
            case 2: a = sub8(v, 0); break;
            case 3: a = sub8(v, f & CF); break;
            case 4: a &= v; f = SZP[a] | HF; break;
            case 5: a ^= v; f = SZP[a]; break;
            case 6: a |= v; f = SZP[a]; break;
            default:
                sub8(v, 0);
                f = (f & ~(XF | YF)) | (v & (XF | YF)); // CP takes bits 3 and 5 from the operand
                break;
        }
    }

    private void add8(int v, int carry) {
        int result = a + v + carry;
        int r8 = result & 0xFF;
        f = SZ[r8] | ((a ^ v ^ result) & HF)
                | ((((a ^ ~v) & (a ^ result)) & 0x80) != 0 ? PF : 0)
                | (result >> 8);
        a = r8;
    }

    private int sub8(int v, int carry) {
        int result = a - v - carry;
        int r8 = result & 0xFF;
        f = SZ[r8] | NF | ((a ^ v ^ result) & HF)
                | ((((a ^ v) & (a ^ result)) & 0x80) != 0 ? PF : 0)
                | ((result >> 8) & CF);
        return r8;
    }

    private int inc8(int v) {
        int result = (v + 1) & 0xFF;
        f = (f & CF) | SZ[result] | (result == 0x80 ? PF : 0) | ((result & 0x0F) == 0 ? HF : 0);
        return result;
    }

    private int dec8(int v) {
        int result = (v - 1) & 0xFF;
        f = (f & CF) | SZ[result] | NF | (result == 0x7F ? PF : 0) | ((result & 0x0F) == 0x0F ? HF : 0);
        return result;
    }

    private void addHl(int v) {
        int hl = hlx();
        int result = hl + v;
        f = (f & (SF | ZF | PF)) | ((result >> 8) & (XF | YF))
                | (((hl ^ v ^ result) >> 8) & HF) | (result >> 16);
        setHlx(result);
    }

    private void adcHl(int v) {
        int hl = hl();
        int result = hl + v + (f & CF);
        int r16 = result & 0xFFFF;
        f = ((r16 >> 8) & (SF | XF | YF)) | (r16 == 0 ? ZF : 0)
                | (((hl ^ v ^ result) >> 8) & HF)
                | ((((hl ^ ~v) & (hl ^ result)) & 0x8000) != 0 ? PF : 0)
                | (result >> 16);
        setHl(r16);
    }

    private void sbcHl(int v) {
        int hl = hl();
        int result = hl - v - (f & CF);
        int r16 = result & 0xFFFF;
        f = ((r16 >> 8) & (SF | XF | YF)) | (r16 == 0 ? ZF : 0) | NF
                | (((hl ^ v ^ result) >> 8) & HF)
                | ((((hl ^ v) & (hl ^ result)) & 0x8000) != 0 ? PF : 0)
                | ((result >> 16) & CF);
        setHl(r16);
    }

    /** Quadrant 0, z 7: RLCA, RRCA, RLA, RRA, DAA, CPL, SCF, CCF. */
    private void accumulatorOp(int y) {
        int preserved = f & (SF | ZF | PF);
        switch (y) {
            case 0: {
                int carry = a >> 7;
                a = ((a << 1) | carry) & 0xFF;
                f = preserved | (a & (XF | YF)) | carry;
                break;
            }
            case 1: {
                int carry = a & 1;
                a = (a >> 1) | (carry << 7);
                f = preserved | (a & (XF | YF)) | carry;
                break;
            }
            case 2: {
                int carry = a >> 7;
                a = ((a << 1) | (f & CF)) & 0xFF;
                f = preserved | (a & (XF | YF)) | carry;
                break;
            }
            case 3: {
                int carry = a & 1;
                a = (a >> 1) | ((f & CF) << 7);
                f = preserved | (a & (XF | YF)) | carry;
                break;
            }
            case 4: daa(); break;
            case 5:
                a ^= 0xFF;
                f = (f & (SF | ZF | PF | CF)) | HF | NF | (a & (XF | YF));
                break;
            case 6:
                f = preserved | (a & (XF | YF)) | CF;
                break;
            default:
                f = (preserved | ((f & CF) << 4) | (a & (XF | YF)) | (f & CF)) ^ CF;
                break;
        }
    }

    private void daa() {
        int correction = 0;
        int carry = f & CF;
        if ((f & HF) != 0 || (a & 0x0F) > 9) {
            correction = 0x06;
        }
        if (carry != 0 || a > 0x99) {
            correction |= 0x60;
            carry = CF;
        }
        boolean subtract = (f & NF) != 0;
        int half;
        if (subtract) {
            half = (f & HF) != 0 && (a & 0x0F) < 6 ? HF : 0;
        } else {
            half = (a & 0x0F) > 9 ? HF : 0;
        }
        a = (subtract ? a - correction : a + correction) & 0xFF;
        f = SZP[a] | half | (f & NF) | carry;
    }
}
