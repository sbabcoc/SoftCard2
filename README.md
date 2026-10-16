# SoftCard2

An emulation of the Microsoft SoftCard II, a Z80 CP/M card for the
Apple II, as a single-board computer: a Z80B with its own 64 KB of RAM
and its own clock, connected to the Apple only through a two-register
mailbox. It plugs into
[ReplicApple2Plus](https://github.com/sbabcoc/ReplicApple2Plus) as a
`SlotCard` (short name `softcard2`).

The hardware model was reconstructed from the CP/M 2.28B boot disk and board photographs; see
[HARDWARE-REFERENCE.md](HARDWARE-REFERENCE.md), which tags every claim with its evidence. **Internet Archive** has preserved a copy of the [original documentation](https://archive.org/details/softcard-ii-installation-and-operations-manual-and-programmers-manual-1/mode/1up)

## Layout

| Package | Contents |
|---|---|
| `com.nordstrom.softcard.z80` | Z80 CPU core with T-state timing and a /WAIT stall |
| `com.nordstrom.softcard.board` | The board: CPU, RAM, mailbox, boot mode, clock. No Apple II dependencies |
| `com.nordstrom.softcard.slot` | The ReplicApple2Plus plug-in card |

## Slot settings

| Key | Default | Meaning |
|---|---|---|
| `z80ClockHz` | `6000000` | Z80 clock frequency |

## Building

ReplicApple2Plus must be in the local Maven repository (`./gradlew install`
in that project), at the version named in `gradle.properties`.

    ./gradlew build

## Verification

- `Z80ExerciserTest` runs Frank Cringle's zexdoc and zexall instruction
  exercisers unmodified. zexdoc passes every test. zexall passes every test
  except `bit n,(hl)`, whose flags come from the MEMPTR latch, which isn't
  modeled.
- `SoftCardBoardTest` drives the board from the host side the way the CP/M
  loader does: the probe, feeding a program through boot mode, the
  mailbox, and the Z80 clock rate.
- `SoftCard2CpmTest` boots the real master disk on ReplicApple2Plus's own
  6502, Language Card and Disk II controller, then runs `DIR`, `SAVE`,
  `STAT`, `ERA` and a warm boot. The disk is Microsoft's and isn't
  included; supply it with `./gradlew test -PbootDisk=/path/to/image.dsk`,
  or the test is skipped.
- `XferDeliveryTest` adds ReplicApple2Plus's host file transfer card in
  slot 2, CP/M's reader slot, and installs the card's CP/M adapter with
  the master disk's own `PIP XFER.HEX=RDR:` and `LOAD XFER`, then runs it.
  It needs the master disk too.
- `XferSessionTest` runs whole transfer sessions with XFER, driven by
  ReplicApple2Plus's `TransferOperations` as its transfer window drives
  them: volumes, listing, text and binary export and import, replacing a
  file, and ending back at the CCP. It needs the master disk too.
- `XferStartTest` runs ReplicApple2Plus's File > Start CP/M Transfer logic
  at the master disk's CCP: installing XFER and starting a session,
  starting it again without reinstalling, and explaining a missing PIP.
  It needs the master disk too.
