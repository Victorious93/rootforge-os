# RootForge OS — build convenience targets
# Victorious Framework | Origin Source Labs
#
# Usage:
#   sudo make build          build the ISO
#   sudo make clean          remove live-build output (keeps cache)
#   sudo make distclean      remove everything including the package cache
#   sudo make flash USB=/dev/sdX   write ISO to a USB drive

ISO     := rootforge-os-amd64.hybrid.iso
LOGFILE := rootforge-build-$(shell date +%Y%m%d_%H%M%S).log

.PHONY: build clean distclean flash check-root

check-root:
	@[ "$$(id -u)" -eq 0 ] || { echo "Run with sudo: sudo make $(MAKECMDGOALS)"; exit 1; }

build: check-root
	@echo "==> Building RootForge OS ISO"
	auto/build 2>&1 | tee $(LOGFILE)

clean: check-root
	lb clean --purge
	rm -f *.log

distclean: check-root
	lb clean --purge
	rm -rf cache/
	rm -f *.log *.iso

flash: check-root
	@[ -n "$(USB)" ] || { echo "Usage: sudo make flash USB=/dev/sdX"; exit 1; }
	@[ -b "$(USB)" ] || { echo "$(USB) is not a block device"; exit 1; }
	@[ -f "$(ISO)" ] || { echo "$(ISO) not found — run 'sudo make build' first"; exit 1; }
	@echo "WARNING: This will overwrite ALL data on $(USB)."
	@echo "Press Ctrl-C within 5 seconds to abort..."
	@sleep 5
	dd if=$(ISO) of=$(USB) bs=4M status=progress oflag=sync
	sync
	@echo "==> Flash complete: $(USB)"
