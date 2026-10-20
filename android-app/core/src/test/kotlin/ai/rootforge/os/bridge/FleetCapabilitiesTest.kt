package ai.rootforge.os.bridge

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetCapabilitiesTest {
    @Test
    fun capabilitiesListsEnvelopeOpsAndRemoteAllowedActions() = runTest {
        val r = parseFleet(fleetExec().handle(fleetJson(nonce = nonce(1), now = NOW), CALLER))
        assertTrue(r.ok)
        assertEquals(1, r.result!!.getValue("protocolMajor").jsonPrimitive.content.toInt())
        assertEquals(1, r.result!!.getValue("protocolMinor").jsonPrimitive.content.toInt())
        assertEquals("external_node", r.result!!.getValue("fleetClass").jsonPrimitive.content)
        assertEquals(NODE, r.result!!.getValue("nodeRef").jsonPrimitive.content)
        assertEquals("android-host-app", r.result!!.getValue("nodeKind").jsonPrimitive.content)

        val ops = r.result!!.getValue("ops").jsonArray.map { it.jsonObject }
        assertEquals(FleetOp.ALL, ops.map { it.getValue("name").jsonPrimitive.content }.toSet())
        ops.forEach { cap ->
            assertTrue(cap.getValue("kind").jsonPrimitive.content in setOf(FleetKind.READ, FleetKind.ACTION, FleetKind.DESTRUCTIVE))
            assertTrue(cap.containsKey("remoteAllowed"))
            assertTrue(cap.containsKey("requiresConfirm"))
        }

        val actions = r.result!!.getValue("actions").jsonArray.map { it.jsonObject }
        val names = actions.map { it.getValue("name").jsonPrimitive.content }.toSet()
        assertTrue(names.contains(BridgeOp.DEVICES_LIST))
        assertTrue(names.contains(BridgeOp.FASTBOOT_FLASH))
        assertTrue(names.contains(RemoteActionCatalog.ACTION_SHIZUKU_PM_GRANT))
        assertFalse(names.contains(RemoteActionCatalog.ACTION_SHIZUKU_EXEC))
        assertFalse(names.any { it.contains("edl") || it.contains("sideload") || it.contains("oem") })
        val flash = actions.single { it.getValue("name").jsonPrimitive.content == BridgeOp.FASTBOOT_FLASH }
        assertEquals(FleetKind.DESTRUCTIVE, flash.getValue("kind").jsonPrimitive.content)
        assertTrue(flash.getValue("requiresConfirm").jsonPrimitive.content.toBoolean())
        val list = actions.single { it.getValue("name").jsonPrimitive.content == BridgeOp.DEVICES_LIST }
        assertEquals(FleetKind.READ, list.getValue("kind").jsonPrimitive.content)
        assertFalse(list.getValue("requiresConfirm").jsonPrimitive.content.toBoolean())

        val export = ops.single { it.getValue("name").jsonPrimitive.content == FleetOp.CONFIG_EXPORT }
        assertEquals(FleetKind.ACTION, export.getValue("kind").jsonPrimitive.content)
        assertFalse(export.getValue("remoteAllowed").jsonPrimitive.content.toBoolean())
        assertFalse(export.getValue("requiresConfirm").jsonPrimitive.content.toBoolean())
        val importOp = ops.single { it.getValue("name").jsonPrimitive.content == FleetOp.CONFIG_IMPORT }
        assertEquals(FleetKind.ACTION, importOp.getValue("kind").jsonPrimitive.content)
        assertFalse(importOp.getValue("remoteAllowed").jsonPrimitive.content.toBoolean())
        assertTrue(importOp.getValue("requiresConfirm").jsonPrimitive.content.toBoolean())
        assertFalse(names.contains(FleetOp.CONFIG_EXPORT))
        assertFalse(names.contains(FleetOp.CONFIG_IMPORT))
    }

    @Test
    fun documentedErrorCodesAreExactlyTheFrozenSet() {
        assertEquals(
            setOf(
                "invalid_parameters", "not_paired", "not_found", "internal", "unauthorized",
                "replay_detected", "expired", "external_control_disabled", "confirmation_required",
                "unsupported_op", "capability_off",
            ),
            FleetError.ALL,
        )
    }
}
