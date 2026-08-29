# RootForge OS — build convenience targets
# Victorious Framework | Origin Source Labs
#
# Usage:
#   make test                run the test suite (no root, no device needed)
#   make lint                shellcheck + Python byte-compile sweep
#   sudo make build          build the ISO
#   make checksum            write rootforge-os-amd64.hybrid.iso.sha256
#   make list-usb            list candidate USB block devices (sanity check before flash)
#   sudo make flash USB=/dev/sdX   write ISO to a USB drive (verifies checksum if present)
#   sudo make clean           remove live-build output (keeps cache)
#   sudo make distclean       remove everything including the package cache
#
# Prebuilt ISOs (and the Termux/PRoot rootfs tarballs) are also published to
# GitHub Releases by .github/workflows/release.yml on tagged pushes — you
# don't have to build locally just to flash a USB drive.

ISO     := rootforge-os-amd64.hybrid.iso
LOGFILE := rootforge-build-$(shell date +%Y%m%d_%H%M%S).log

.PHONY: build clean distclean checksum list-usb flash check-root test lint

# Neither target needs root: the tests stub out adb/fastboot and write only
# to a scratch HOME. tests/lint.sh is the single definition of "lint" here,
# shared with the CI workflow so the two can't drift apart.
test:
	tests/run-tests.sh

lint:
	tests/lint.sh

check-root:
	@[ "$$(id -u)" -eq 0 ] || { echo "Run with sudo: sudo make $(MAKECMDGOALS)"; exit 1; }

build: check-root
	@echo "==> Building RootForge OS ISO"
	auto/build 2>&1 | tee $(LOGFILE)
	$(MAKE) checksum

clean: check-root
	lb clean --purge
	rm -f *.log

distclean: check-root
	lb clean --purge
	rm -rf cache/
	rm -f *.log *.iso *.iso.sha256

checksum:
	@[ -f "$(ISO)" ] || { echo "$(ISO) not found — run 'sudo make build' first"; exit 1; }
	sha256sum $(ISO) > $(ISO).sha256
	@echo "==> $(ISO).sha256 written: $$(cat $(ISO).sha256)"

list-usb:
	@echo "==> Removable block devices (double-check against 'sudo make flash USB=...'):"
	@lsblk -d -o NAME,SIZE,RM,TYPE,MODEL 2>/dev/null | awk 'NR==1 || $$3=="1"'

flash: check-root
	@[ -n "$(USB)" ] || { echo "Usage: sudo make flash USB=/dev/sdX  (run 'make list-usb' first if unsure)"; exit 1; }
	@[ -b "$(USB)" ] || { echo "$(USB) is not a block device"; exit 1; }
	@[ -f "$(ISO)" ] || { echo "$(ISO) not found — run 'sudo make build' first, or download a release ISO"; exit 1; }
	@if [ -f "$(ISO).sha256" ]; then \
		echo "==> Verifying $(ISO) against $(ISO).sha256"; \
		sha256sum -c $(ISO).sha256 || { echo "Checksum mismatch — refusing to flash a possibly-corrupt ISO."; exit 1; }; \
	else \
		echo "==> No $(ISO).sha256 found — skipping verification (run 'make checksum' first to be sure)"; \
	fi
	@echo "==> Target device:"
	@lsblk -d -o NAME,SIZE,MODEL "$(USB)" 2>/dev/null || true
	@echo "WARNING: This will overwrite ALL data on $(USB)."
	@echo "Press Ctrl-C within 5 seconds to abort..."
	@sleep 5
	dd if=$(ISO) of=$(USB) bs=4M status=progress oflag=sync
	sync
	@echo "==> Flash complete: $(USB)"
