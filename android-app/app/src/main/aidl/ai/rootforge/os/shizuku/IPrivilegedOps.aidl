package ai.rootforge.os.shizuku;

/**
 * Read-only privileged ops on this phone. Transaction 16777114 is Shizuku's
 * reserved destroy. Do not add flash, unlock, wipe or write methods here.
 */
interface IPrivilegedOps {
    void destroy() = 16777114;

    String listPackages(boolean thirdPartyOnly) = 1;
    String packageInfo(String packageName) = 2;
    String settingsGet(String namespace, String key) = 3;
    String getprop(String key) = 4;
    String listBlockDevices() = 5;
    String deviceInfo() = 6;
}
