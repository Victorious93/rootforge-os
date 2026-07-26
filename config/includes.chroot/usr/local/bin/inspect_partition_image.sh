#!/usr/bin/env bash
# RootForge OS — partition image inspector
# Victorious Framework
#
# Mounts an extracted ext4/erofs partition image read-only via a loop
# device so you can browse it without flashing anything. Always mounts
# -o ro — this is inspection tooling, not an editor.
#
# Usage: sudo ./inspect_partition_image.sh <image.img> [mount_point]

set -euo pipefail

IMG="${1:?Usage: inspect_partition_image.sh <image.img> [mount_point]}"
MOUNT_POINT="${2:-/mnt/rootforge_inspect_$(basename "$IMG" .img)}"

[[ -f "$IMG" ]] || { echo "Image not found: $IMG" >&2; exit 1; }
if [[ $EUID -ne 0 ]]; then
  echo "Loop mounting requires root — re-run with sudo." >&2
  exit 1
fi

mkdir -p "$MOUNT_POINT"

FSTYPE="$(file -b "$IMG" | grep -oiE 'ext[234]|erofs|squashfs' | head -1 | tr 'A-Z' 'a-z' || true)"

if [[ -z "$FSTYPE" ]]; then
  echo "Could not auto-detect filesystem type via 'file'. Common cases:"
  echo "  boot.img / init_boot.img — these are NOT filesystems, they're a header +"
  echo "    kernel + ramdisk cpio archive. Use magiskboot --unpack instead of this script."
  echo "  raw sparse image — convert first: simg2img '$IMG' '${IMG%.img}_raw.img'"
  exit 1
fi

echo "Detected filesystem: $FSTYPE"
echo "Mounting read-only at $MOUNT_POINT"

case "$FSTYPE" in
  ext2|ext3|ext4)
    mount -o ro,loop -t ext4 "$IMG" "$MOUNT_POINT"
    ;;
  erofs)
    mount -o ro,loop -t erofs "$IMG" "$MOUNT_POINT" 2>/dev/null || \
      { echo "erofs mount failed — kernel may lack erofs support. Try: apt install erofs-utils and 'fsck.erofs --extract=$MOUNT_POINT $IMG' instead."; exit 1; }
    ;;
  squashfs)
    mount -o ro,loop -t squashfs "$IMG" "$MOUNT_POINT"
    ;;
esac

echo "Mounted at $MOUNT_POINT (read-only). Unmount when done:"
echo "  sudo umount $MOUNT_POINT"

# Victorious Framework
