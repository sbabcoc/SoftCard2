# Microsoft SoftCard II — Hardware Reference

No schematic, manual or other emulation of this card is known to exist.
Everything here was reconstructed from the CP/M 2.28B master disk and two
photographs of a REV A board. Each claim is tagged with its evidence:

| Tag | Meaning |
|---|---|
| **[6502]** | Read from the 6502 code on the boot disk |
| **[BIOS]** | Read from the Z80 BIOS on the boot disk |
| **[photo]** | Read from the board photographs |
| **[run]** | Confirmed by booting the real disk on this emulation (`SoftCardIICpmTest`) |
| **[inferred]** | The simplest hardware consistent with the code; not observed directly |

Addresses in the 6502 code are given where they run: the loader in
language-card RAM at `$D100–$E1FF`, the I/O server after it is copied from
`$DA00–$E2FF` to `$0C00–$14FF`.

## The board

A single-board computer that shares nothing with the Apple II but a
mailbox. **[photo]**

| Part | Function |
|---|---|
| U21 SGS Z8400BB1 | Z80B CPU, rated 6 MHz |
| Y1 12.000 MHz oscillator | Z80 clock source |
| U1–U8 OKI M3764-15 | 64K×1 DRAM: 64 KB private to the Z80 |
| U9, U10 74LS258 | DRAM row/column address multiplexers |
| U20 AMD Am2950 | 8-bit bidirectional I/O port with handshake: the mailbox |
| U12–U19, U22–U23, one 74LS04 | Glue: 74LS10, 74LS00, two 74LS74, two 74LS155, 74LS32, 74LS08, two 74LS125 |

There is no ROM, PROM or PAL on the board. Boot mode must therefore be held
in one of the four 74LS74 flip-flops. **[photo]** **[inferred]**

The Z80 runs on its own clock, so its timing is independent of the Apple's
bus. A divide-by-two of the oscillator would give 6 MHz, matching the
Z80B's rating, but the divider isn't visible. **[inferred]** The emulation
defaults to 6 MHz and takes `z80ClockHz` as a slot setting.

## Mailbox

The Am2950 holds one 8-bit register per direction. Each register has a
flag that sets when the register is loaded and clears when it is read.
**[photo]** Each processor sees the flags from its own side. **[6502]**
**[BIOS]**

| Bit | Meaning on either side |
|---|---|
| 7 | A byte is waiting for me |
| 0 | The byte I sent hasn't been taken yet |

The other six status bits are never examined by any known code, and their
value is unknown. The emulation returns 0 for them.

### 6502 side, slot n

| Address | Read | Write |
|---|---|---|
| `$C0n0` | Byte from the Z80; clears its flag **[6502]** | Byte to the Z80; sets its flag **[6502]** |
| `$C0n1` | Status **[6502]** | Ignored: every send polls with `ROR $C0n1`, a read-modify-write that writes here **[6502]** **[run]** |
| `$C0n3` | — | Reset the Z80 and enter boot mode **[inferred]** **[run]** |

The 6502 sends with `ROR $C0n1 / BCS` (wait for bit 0 clear), then
`STA $C0n0`. It receives with `LDA $C0n1 / BPL` (wait for bit 7 set), then
`LDA $C0n0`. **[6502]**

The card has no slot ROM, so `$Cn00–$CnFF` reads as the floating bus.
**[photo]** **[run]**

### Z80 side

| Port | Read | Write |
|---|---|---|
| 0 | Byte from the 6502 | Byte to the 6502 |
| 1 | Status | — |

Only A0 is decoded as far as the code shows. **[inferred]** The BIOS polls
`IN A,(1)` with `RLA / JR NC` before reading and with `RRA / JR C` before
writing. **[BIOS]**

## Boot mode

The Z80 has no ROM. After a reset it starts in boot mode, and the 6502
feeds it instructions through the mailbox. **[6502]** **[run]**

- Z80 memory accesses with A15 low go to the mailbox: a read takes the
  6502's byte, a write hands a byte to the 6502. Accesses with A15 high go
  to RAM. **[inferred]** **[run]** This decode is the simplest one that
  fits: the probe's write to `$0000` reaches the 6502, while the fed
  `LD (HL),n` writes to `$8000+` land in RAM and `JP 8000h` fetches from it.
- A read from the mailbox while it is empty holds the Z80 until the 6502
  writes. **[inferred]** **[run]** Without this, the Z80 would execute stale
  bytes, since the 6502 feeds opcodes far more slowly than the Z80 fetches
  them. On the board this implies the input flag drives /WAIT.
- The first Z80 I/O write ends boot mode and latches nothing. **[inferred]**
  **[run]** The bootstrap's first instruction is `OUT (0),A` with A = `$A5`.
  Nothing on the 6502 side drains a byte before the command loop, which
  would otherwise take `$A5` as a command and jump through `$AAB2`. **[6502]**
- The Apple's RESET line (edge connector pin 31 is wired) is taken to have
  the same effect as a write to `$C0n3`. **[photo]** **[inferred]**

## Boot sequence

1. **Loader.** Physical sector 0 loads physical sectors 2, 4, … 14, 1, 3, …
   15 of track 0 into `$0A00`. Unless booting from slot 6, it prints "MUST
   BOOT FROM SLOT SIX". It then copies the code into language-card bank 2
   at `$D100` and reads track 1 sectors 0–1 into `$E000`. **[6502]**
2. **Slot scan** (`$D95B`). For slots 7 down to 1, it sums the 256 bytes
   of `$Cn00` twice. If the sums differ, or the carry count is 0, the slot
   is empty. Otherwise the ID bytes at `$Cn05`/`$Cn07` give the type.
   **[6502]** **[run]**

   | Type | `$Cn05`/`$Cn07` | Card |
   |---|---|---|
   | 0 | (unstable) | Empty |
   | 1 | no match | Unrecognized ROM |
   | 2 | `$03`/`$3C` | Disk II |
   | 3 | `$18`/`$38` | 6850 ACIA serial card (Apple Communications Card) |
   | 4 | `$38`/`$18` | Card with `$C800`-ROM entry points, such as the Videx VideoTerm **[run]** |
   | 5 | `$48`/`$48` | Parallel printer card |
   | 6 | `$38`/`$18`, `$Cn0B` = `$01` | Pascal 1.1 firmware (e.g. Super Serial Card) |

   An empty slot 3, or a type-5 card there, becomes type 1, the built-in
   console. **[6502]** The type table is sent to the Z80, which stores it
   at `$F3B8` and changes an unrecognized card in slot 3 to type 4. **[BIOS]**
3. **Probe** (`$DA27`). For each slot of type 0 or 1, the 6502 writes
   `$C0n3`, reads `$C0n0`, and writes `3E 5A 2F 32 00 00`
   (`LD A,5Ah / CPL / LD (0000h),A`) to `$C0n0` without polling. A card
   answers `$A5`. **[6502]** **[run]**
4. **Bootstrap.** The 6502 writes `$C0n3` again and feeds `LD HL,8000h`,
   61 × (`LD (HL),n / INC HL`), `JP 8000h`. The 61-byte program at `$8000`
   leaves boot mode, plants `JP F900h` at `$0000`, receives `$E200–$FFFF`
   and the 8-byte slot table, and jumps to the BIOS at `$F900`. **[6502]**
   **[run]**
5. **Server.** The 6502 patches its sector-read routine to stream decoded
   data into the mailbox, copies the server to `$0C00`, sets its vectors at
   `$FFFA`, and enters the command loop at `$0D44`. **[6502]**

## Command protocol

The Z80 sends a command byte and its parameters; the 6502 does the work and
replies. The 6502 dispatches through a 17-entry table at `$0D63`/`$0D74`.
The BIOS has a matching 17-entry table of senders at `$FACB`, entered
through `$FBA0` with the command number in `$0049`. Both tables agree on
every count below, and every command has been run. **[6502]** **[BIOS]**
**[run]**

Commands 3–9 and 11 are exercised by booting and using CP/M. Commands 0, 1,
2, 10 and 12–16 were run from DDT by calling `$FBA0`: command 2 wrote `$0300`,
command 1 read it back, command 0 called the Monitor's BELL at `$FF3A`,
command 12 sounded its tone, and command 10 formatted a blank disk in drive 2
(status `$00`; CP/M then read its empty directory). Commands 13–16 were run
the same way with hi-res page 1 displayed (switched on through command 2):
the fill covered the screen, the invert changed all of it, and the masked
and XOR writes set and then cleared the pixels of byte `$2000`. **[run]**

| # | Z80 → 6502 | Action | 6502 → Z80 |
|---|---|---|---|
| 0 | address (2), A, X, Y | Read ROM (`$C081`), call the 6502 subroutine | A, X, Y, P |
| 1 | address (2) | Read 6502 memory | byte |
| 2 | address (2), value | Write 6502 memory | — |
| 3 | track, drive, slot, sector | Read a sector | status; then 256 bytes if status is 0 |
| 4 | track, drive, slot, sector, 256 bytes | Write a sector | status |
| 5 | slot | Character input | byte |
| 6 | slot, then character after the reply | Character output | 1 byte (console input status), sent between the slot and the character |
| 7 | slot | Character status | `$FF` ready, `$00` not |
| 8 | slot | Initialize device | — |
| 9 | — | Warm boot: stream `$E200–$FFFF` again | 7,680 bytes |
| 10 | slot, drive | Format all 35 tracks | status |
| 11 | 34 bytes | Load the terminal definition into `$141C`, `$140D`, `$1400` | — |
| 12 | 2 bytes | Beep | — |
| 13 | 2 bytes | Fill hi-res page 1 with an even/odd byte pair | — |
| 14 | — | Invert hi-res page 1 | — |
| 15 | address (2), value, mask | Masked write | — |
| 16 | address (2), mask | XOR write | — |

- **Addresses** are low byte first. **[6502]**
- **Sectors** are logical, mapped through the skew table
  `0,2,4,6,8,A,C,E,1,3,5,7,9,B,D,F`. An odd drive number selects drive 1.
  **[6502]** The slot is the plain slot number. **[6502]**
- **Status** is `$00` OK, `$10` write-protected, `$20` volume mismatch, or
  `$40` I/O error after retries. **[6502]** The BIOS returns early on any
  nonzero status. **[BIOS]**
- **Command 4** sends its data last byte first. The 6502 stores it with a
  decrementing index, so the order matches. **[BIOS]** **[6502]**
- **Commands 5–8** treat slot 3 as the console. Input there comes from a
  type-ahead buffer at `$1500`, and output runs through a terminal emulator
  configured by command 11. Every other slot dispatches by card type
  through the vectors at `$0F8F`. **[6502]**

  | Type | Input | Output | Status | Init |
  |---|---|---|---|---|
  | 0 | returns `$1A` (^Z) | — | `$FF` | — |
  | 1 | `$C000` keyboard | built-in screen | `$C000` bit 7 | — |
  | 3 | 6850 at `$C0nE`/`$C0nF` | 6850; slot 1 spools to `$4000–$BFFF` | 6850 | `$03`, then `$15` |
  | 4 | `$C84D` | `$C9AA` | keyboard | `$C800` |
  | 5 | returns `$1A` (^Z) | waits on `$CnC1` bit 7, writes `$C0n0`; slot 1 spools to `$4000–$BFFF` | `$CnC1` bit 7 | — |
  | 6 | Pascal `$Cn0E` | Pascal `$Cn0F` | Pascal `$Cn10` | Pascal `$Cn0D` |

## Built-in console

The type-1 screen driver switches between main and auxiliary 80-column
memory with `$C054`/`$C055` on every character. It assumes the IIe's
80STORE, set at server start with `$C001`. No machine-ID check selects it.
**[6502]** On an Apple II+ with slot 3 empty, both halves of each
character pair land in main memory, and every other character is lost.
**[run]** This agrees with the packaging's requirement for an 80-column
card on a II+: there, the console is the card in slot 3. With a Videx
VideoTerm in slot 3, CP/M boots and runs on the 80-column display through
the type-4 entry points. **[run]**

## Unknowns

- The exact boot-mode decode and boot-mode exit. The model above is the
  simplest one consistent with the code, and it boots and runs CP/M.
- The function, if any, of `$C0n2` and `$C0n4–$C0nF`.
- The six unused status bits.
- The Z80 clock divider.
- Whether the Am2950 flags are cleared on reset.
- Whether /INT or /NMI is connected.
