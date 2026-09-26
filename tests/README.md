# RootForge OS — test suite

Fast, hermetic checks that run without a device, without Docker, and without
network access.

"Hermetic" is a claim the suite has to keep earning. It did not always: the
`harden_kernel.sh` section ran the real script, which does
`sudo tee /etc/sysctl.d/90-rootforge-hardening.conf` and `sudo sysctl --system`
— so a run made as root modified the machine running the tests, verifiably
(the drop-in was present on the host, timestamped by the last run). Scripts
that write outside `$ROOTFORGE_HOME` therefore take an environment seam for
each destination, and `new_sandbox` sets them:

| Seam | Redirects |
|---|---|
| `ROOTFORGE_SYSCTL_FILE` | the sysctl drop-in (and skips `sysctl --system`) |
| `ROOTFORGE_GRUB_DEFAULTS` | `/etc/default/grub` (and skips `update-grub`) |
| `ROOTFORGE_USBGUARD_RULES` | `/etc/usbguard/rules.conf` |
| `ROOTFORGE_AUDIT_RULES` | the auditd rules drop-in |
| `ROOTFORGE_NFT_FILE` | `/etc/nftables.conf` |

Two rules follow from this. A test that reaches a script writing outside the
sandbox must use the seam, and `00_bootstrap_distro.sh` must always be given
`--check` — without it the script really does run `apt-get upgrade`, and a
suite run as root will let it.

`new_sandbox` also unsets every seam and stub variable. A value leaking into
the next sandbox makes a test pass or fail for a reason that is nowhere in its
own body; that has happened twice here.

## Checks on the suite itself

    tests/check-tests.sh        # run by tests/lint.sh and by CI

A suite can be wrong in ways that running it will never reveal, because a
wrong suite still passes. Two such failures were real here, so they are now
checked statically:

- **A block that never invokes the code it names.** One block built a USBGuard
  policy file and grepped it; another re-implemented the WireGuard address
  allocator and asserted on its own output. Both would have passed whether or
  not the shipped code had the fix. Granularity is the `new_sandbox` block,
  not the `section` — the USBGuard section *did* run the script, in a
  different block, so a section-level check passes it.

- **A write destination with no seam.** Covered by the table above.

Running new checks against the pre-fix tree catches tests that pass for the
wrong reason. It does not catch tests that were already wrong. This does. They exist because every bug they cover was a *silent* one:
wrong-but-plausible behavior that a build would not catch and a human would
only notice with a phone in hand halfway through a flash.

    tests/run-tests.sh          # everything
    tests/run-tests.sh shell    # shell-script behavior only
    tests/run-tests.sh python   # Python unit tests only

## How the shell tests work

The scripts under test shell out to `adb` and `fastboot`. `tests/stubs/`
holds fake ones that are put first on `PATH`: they print canned output and
record every invocation to `$RF_STUB_LOG`, so a test can assert on the exact
command line a script *would* have run against real hardware. Nothing here
touches a device.

Destructive scripts stop at their typed-confirmation gate, which
`rf_confirm` reads from `/dev/tty`. The tests exercise both sides of that
gate: with no terminal available it must refuse (so an unattended run never
flashes anything), and with `ROOTFORGE_ASSUME_YES=1` it must proceed.

`HOME` is redirected to a scratch directory for every test, so logs and
backups land there rather than in the real `~/rootforge`.
