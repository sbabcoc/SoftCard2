# TODO

- **Clock the card inside ReplicApple2Plus.** `Apple2Plus` registers cycle
  listeners only for the Disk II controller, by class, so a plug-in card is
  never ticked and the Z80 never runs. The card needs a generic hook, such
  as a default `tick(int cycles)` on `SlotCard` registered for every card.
- **Boot with the VideoTerm in slot 3.** On a II+ the console must be an
  80-column card. Confirm that the type-4 entry points the server calls
  (`$C800` init, `$C84D` input, `$C9AA` output) are VideoTerm firmware
  entry points, and that CP/M runs on the VideoTerm.
- **Pin down the Z80 clock.** Find CP/M software that times itself or is
  speed-sensitive, and use it to confirm or correct the assumed 6 MHz.
- **Exercise the remaining commands.** Commands 0–2, 10 and 12–16 are
  verified only by reading code on both sides, not by running them.
- **Find more evidence.** Other SoftCard II disks, or better photos of the
  component side, could settle the boot-mode decode, `$C0n3`, and the
  unused status bits.
