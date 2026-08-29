# RootForge OS — test suite

Fast, hermetic checks that run without a device, without Docker, and without
network access. They exist because every bug they cover was a *silent* one:
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
