package ai.rootforge.os.bridge

import ai.rootforge.os.root.RootManager
import java.io.File

/**
 * Builds this node's [NodeStatusSnapshot]. Default reports `node_health`
 * only. Android supplies [packageName] and a [RootPulse] so an `other`
 * domain can say whether `su` looks present and which manager APK is
 * installed — without running `su` (that can pop a grant prompt).
 */
fun interface NodeStatusSource {
    fun snapshot(nodeId: String, nowMs: Long): NodeStatusSnapshot

    companion object {
        val Default: NodeStatusSource = NodeStatusSource { nodeId, nowMs ->
            NodeStatusBuilder.build(nodeId = nodeId, nowMs = nowMs)
        }
    }
}

/** Non-secret root pulse for the `other` STATUS-TRAY domain. */
data class RootPulse(
    val suAvailable: Boolean,
    val managerLabel: String? = null,
) {
    fun state(): StatusTrayState = when {
        suAvailable -> StatusTrayState.OK
        !managerLabel.isNullOrBlank() -> StatusTrayState.DEGRADED
        else -> StatusTrayState.OFF
    }

    fun detail(): String {
        val parts = ArrayList<String>(2)
        parts += if (suAvailable) "su available" else "su not available"
        val manager = managerLabel?.trim().orEmpty()
        if (manager.isNotEmpty()) parts += "$manager detected"
        return parts.joinToString("; ").take(StatusTrayDomainStatus.MAX_DETAIL_CHARS)
    }

    companion object {
        /** Common su locations. Presence is [File.exists]; `su` is never executed. */
        val SU_PATHS: List<String> = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
        )

        fun suBinaryPresent(exists: (String) -> Boolean = { File(it).exists() }): Boolean =
            SU_PATHS.any { path -> runCatching { exists(path) }.getOrDefault(false) }
    }
}

object NodeStatusBuilder {
    const val HEALTH_DETAIL = "process alive"

    fun build(
        nodeId: String,
        nowMs: Long,
        packageName: String? = null,
        connectionClass: String = FleetConnectionClass.EXTERNAL_NODE,
        healthState: StatusTrayState = StatusTrayState.OK,
        healthDetail: String? = HEALTH_DETAIL,
        root: RootPulse? = null,
    ): NodeStatusSnapshot {
        val domains = ArrayList<StatusTrayDomainStatus>(2)
        domains += StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, healthState, healthDetail)
        if (root != null) {
            domains += StatusTrayDomainStatus(StatusTrayDomain.OTHER, root.state(), root.detail())
        }
        return NodeStatusSnapshot(
            nodeId = nodeId,
            packageName = packageName?.takeIf { it.isNotBlank() },
            connectionClass = connectionClass,
            updatedAtMs = nowMs,
            domains = domains,
        )
    }
}

/**
 * Live probe used by the Android host-phone app. [installedManagers] is
 * PackageManager package names; [RootManager.detect] picks a label.
 * [suPresent] defaults to looking for a su binary, never executing it.
 */
class ProbingNodeStatusSource(
    private val packageName: String?,
    private val installedManagers: () -> Collection<String> = { emptyList() },
    private val suPresent: () -> Boolean = { RootPulse.suBinaryPresent() },
    private val connectionClass: String = FleetConnectionClass.EXTERNAL_NODE,
) : NodeStatusSource {
    override fun snapshot(nodeId: String, nowMs: Long): NodeStatusSnapshot {
        val detection = RootManager.detect(installedManagers())
        return NodeStatusBuilder.build(
            nodeId = nodeId,
            nowMs = nowMs,
            packageName = packageName,
            connectionClass = connectionClass,
            root = RootPulse(
                suAvailable = suPresent(),
                managerLabel = detection.preferred?.label,
            ),
        )
    }
}
