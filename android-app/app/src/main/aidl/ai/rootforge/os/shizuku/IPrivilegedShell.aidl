// Fleet Shizuku standard UserService AIDL.
// destroy() MUST use transaction 16777114 — Shizuku calls it when unbinding.
package ai.rootforge.os.shizuku;

interface IPrivilegedShell {
    void destroy() = 16777114;

    String exec(in String[] argv, long timeoutMs) = 1;

    String readDeviceState() = 2;

    String pmGrant(String packageName, String permission) = 3;
}
