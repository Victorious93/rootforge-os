package ai.rootforge.os.config

import ai.rootforge.os.bridge.BRIDGE_PACKAGE
import ai.rootforge.os.bridge.FleetError
import ai.rootforge.os.bridge.bridgeJson
import ai.rootforge.os.core.ApprovalCenter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigBackupTest {
    private fun storeWithLive(): MemoryNodeConfigStore = MemoryNodeConfigStore().also {
        it.setPreference(ConfigPreferenceId.AUTO_CONNECT, "true")
        it.setPreference(ConfigPreferenceId.TERMINAL_APP, "com.termux")
        it.secrets["magiskToken"] = "magisk-superuser-token"
        it.secrets["api_token"] = "desktop-bearer"
        it.secrets["rootPassword"] = "toor"
        it.secrets["pairingKey"] = "aa".repeat(32)
        it.secrets["keystorePassword"] = "changeit"
    }

    private fun backup(
        store: NodeConfigStore = storeWithLive(),
        approvals: ApprovalCenter = ApprovalCenter(),
    ) = ConfigBackup(store, approvals, clock = { 1_700_000_000_000L })

    private fun incomingBundle(
        autoConnect: String = "false",
        terminalApp: String = "com.other.term",
        extras: List<Pair<String, String>> = emptyList(),
        extraPayloadFields: JsonObject = JsonObject(emptyMap()),
        schemaVersion: Int = CONFIG_PAYLOAD_SCHEMA,
        version: Int = CONFIG_BUNDLE_VERSION,
        wrapping: String = ConfigWrapping.NONE,
        packageName: String = BRIDGE_PACKAGE,
        extraBundleFields: JsonObject = JsonObject(emptyMap()),
        extraEntryFields: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val preferences = buildJsonArray {
            add(buildJsonObject { put("id", ConfigPreferenceId.AUTO_CONNECT); put("value", autoConnect) })
            add(buildJsonObject { put("id", ConfigPreferenceId.TERMINAL_APP); put("value", terminalApp) })
            extras.forEach { (id, value) ->
                add(buildJsonObject { put("id", id); put("value", value) })
            }
        }
        val payload = buildJsonObject {
            put("schemaVersion", schemaVersion)
            put("preferences", preferences)
            extraPayloadFields.forEach { (k, v) -> put(k, v) }
        }
        val entry = buildJsonObject {
            put("packageName", packageName)
            put("schemaVersion", schemaVersion)
            put("clearPayload", payload.toString())
            extraEntryFields.forEach { (k, v) -> put(k, v) }
        }
        return buildJsonObject {
            put("version", version)
            put("createdAtMs", 1_700_000_000_000L)
            put("sourceNodeRef", "controller")
            put("wrapping", wrapping)
            put("entries", buildJsonArray { add(entry) })
            extraBundleFields.forEach { (k, v) -> put(k, v) }
        }
    }

    @Test
    fun exportOmitsSecretsAndHiddenStoreKeys() {
        val store = storeWithLive()
        val out = backup(store).export()
        val clear = out.getValue("clearPayload").jsonPrimitive.content
        assertFalse(clear.contains("magiskToken"))
        assertFalse(clear.contains("magisk-superuser-token"))
        assertFalse(clear.contains("api_token"))
        assertFalse(clear.contains("desktop-bearer"))
        assertFalse(clear.contains("rootPassword"))
        assertFalse(clear.contains("toor"))
        assertFalse(clear.contains("pairingKey"))
        assertFalse(clear.contains("keystorePassword"))
        assertFalse(clear.contains("changeit"))
        assertFalse(out.toString().contains("externalControlEnabled"))
        val prefs = bridgeJson.parseToJsonElement(clear).jsonObject.getValue("preferences").jsonArray
        val ids = prefs.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        assertEquals(ConfigPreferenceId.EXPORTABLE, ids)
        assertEquals("true", prefs.single { it.jsonObject.getValue("id").jsonPrimitive.content == "autoConnect" }.jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(BRIDGE_PACKAGE, out.getValue("packageName").jsonPrimitive.content)
        assertEquals(CONFIG_PAYLOAD_SCHEMA, out.getValue("schemaVersion").jsonPrimitive.content.toInt())
    }

    @Test
    fun dryRunReportsChangesAndWritesNothing() = runTest {
        val store = storeWithLive()
        val result = backup(store).import(
            bundleText = incomingBundle().toString(),
            dryRun = true,
            conflictPolicy = ConfigConflictPolicy.OVERWRITE,
        )
        assertTrue(result.getValue("dryRun").jsonPrimitive.content.toBoolean())
        assertEquals("true", store.getPreference(ConfigPreferenceId.AUTO_CONNECT))
        assertEquals("com.termux", store.getPreference(ConfigPreferenceId.TERMINAL_APP))
        assertFalse(store.externalControlEnabled)
        val changes = result.getValue("changes").jsonArray
        assertEquals(2, changes.size)
        assertTrue(changes.all { it.jsonObject.getValue("action").jsonPrimitive.content == "overwrite" })
    }

    @Test
    fun skipKeepsLiveRowsAndDropsImportedIds() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryNodeConfigStore().also {
            it.setPreference(ConfigPreferenceId.AUTO_CONNECT, "true")
        }
        val approvals = ApprovalCenter()
        val pending = async {
            backup(store, approvals).import(
                bundleText = incomingBundle().toString(),
                conflictPolicy = ConfigConflictPolicy.SKIP,
            )
        }
        approvals.answer(approvals.pending.value.single().id, true)
        val result = pending.await()
        assertEquals("true", store.getPreference(ConfigPreferenceId.AUTO_CONNECT))
        assertEquals(null, store.getPreference(ConfigPreferenceId.TERMINAL_APP))
        assertEquals(2, result.getValue("skippedCount").jsonPrimitive.content.toInt())
        assertEquals(0, result.getValue("appliedCount").jsonPrimitive.content.toInt())
    }

    @Test
    fun overwriteReplacesLiveRows() = runTest(UnconfinedTestDispatcher()) {
        val store = storeWithLive()
        val approvals = ApprovalCenter()
        val pending = async {
            backup(store, approvals).import(
                bundleText = incomingBundle().toString(),
                conflictPolicy = ConfigConflictPolicy.OVERWRITE,
            )
        }
        approvals.answer(approvals.pending.value.single().id, true)
        pending.await()
        assertEquals("false", store.getPreference(ConfigPreferenceId.AUTO_CONNECT))
        assertEquals("com.other.term", store.getPreference(ConfigPreferenceId.TERMINAL_APP))
    }

    @Test
    fun mergeByIdFillsGapsAndLiveWinsOnCollision() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryNodeConfigStore().also {
            it.setPreference(ConfigPreferenceId.AUTO_CONNECT, "true")
        }
        val approvals = ApprovalCenter()
        val pending = async {
            backup(store, approvals).import(
                bundleText = incomingBundle().toString(),
                conflictPolicy = ConfigConflictPolicy.MERGE_BY_ID,
            )
        }
        approvals.answer(approvals.pending.value.single().id, true)
        val result = pending.await()
        assertEquals("true", store.getPreference(ConfigPreferenceId.AUTO_CONNECT))
        assertEquals("com.other.term", store.getPreference(ConfigPreferenceId.TERMINAL_APP))
        val byId = result.getValue("changes").jsonArray.associate {
            it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("action").jsonPrimitive.content
        }
        assertEquals("skip", byId[ConfigPreferenceId.AUTO_CONNECT])
        assertEquals("add", byId[ConfigPreferenceId.TERMINAL_APP])
    }

    @Test
    fun importRequiresOnDeviceConfirm() = runTest(UnconfinedTestDispatcher()) {
        val store = storeWithLive()
        val approvals = ApprovalCenter()
        val pending = async {
            runCatching {
                backup(store, approvals).import(
                    bundleText = incomingBundle().toString(),
                    conflictPolicy = ConfigConflictPolicy.OVERWRITE,
                )
            }
        }
        val req = approvals.pending.value.single()
        assertEquals("Import settings?", req.title)
        assertTrue(req.phrase == null)
        approvals.answer(req.id, false)
        val result = pending.await()
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as ConfigBackupException
        assertEquals(FleetError.CONFIRMATION_REQUIRED, err.code)
        assertEquals("true", store.getPreference(ConfigPreferenceId.AUTO_CONNECT))
        assertEquals("com.termux", store.getPreference(ConfigPreferenceId.TERMINAL_APP))
    }

    @Test
    fun importNeverEnablesExternalControlOrRemoteCommand() = runTest(UnconfinedTestDispatcher()) {
        val off = storeWithLive()
        val approvals = ApprovalCenter()
        val withRemoteFlags = incomingBundle(
            extraPayloadFields = buildJsonObject {
                put("externalControlEnabled", JsonPrimitive(true))
                put("bridgeEnabled", JsonPrimitive(true))
                put("remote.command", JsonPrimitive(true))
            },
        )
        val denied = runCatching {
            backup(off, approvals).import(bundleText = withRemoteFlags.toString(), dryRun = true)
        }
        assertEquals(FleetError.INVALID_PARAMETERS, (denied.exceptionOrNull() as ConfigBackupException).code)
        assertFalse(off.externalControlEnabled)

        val pending = async {
            backup(off, approvals).import(
                bundleText = incomingBundle().toString(),
                conflictPolicy = ConfigConflictPolicy.OVERWRITE,
            )
        }
        approvals.answer(approvals.pending.value.single().id, true)
        val result = pending.await()
        assertFalse(off.externalControlEnabled)
        assertFalse(result.getValue("externalControlEnabled").jsonPrimitive.content.toBoolean())

        val alreadyOn = storeWithLive().also { it.externalControlEnabled = true }
        val onApprovals = ApprovalCenter()
        val pendingKeep = async {
            backup(alreadyOn, onApprovals).import(
                bundleText = incomingBundle().toString(),
                conflictPolicy = ConfigConflictPolicy.OVERWRITE,
            )
        }
        onApprovals.answer(onApprovals.pending.value.single().id, true)
        pendingKeep.await()
        assertTrue("import must leave an already-on switch as-is", alreadyOn.externalControlEnabled)
    }

    @Test
    fun unknownVersionAndOversizedPayloadAreRejected() = runTest {
        val store = storeWithLive()
        val badVersion = runCatching {
            backup(store).import(bundleText = incomingBundle(version = 99).toString(), dryRun = true)
        }
        assertEquals(FleetError.INVALID_PARAMETERS, (badVersion.exceptionOrNull() as ConfigBackupException).code)

        val unknownKey = runCatching {
            backup(store).import(
                bundleText = incomingBundle(extraBundleFields = buildJsonObject { put("role", JsonPrimitive("admin")) }).toString(),
                dryRun = true,
            )
        }
        assertEquals(FleetError.INVALID_PARAMETERS, (unknownKey.exceptionOrNull() as ConfigBackupException).code)

        val huge = "x".repeat(CONFIG_MAX_BYTES + 8)
        val oversized = runCatching { backup(store).import(bundleText = huge, dryRun = true) }
        assertEquals(FleetError.INVALID_PARAMETERS, (oversized.exceptionOrNull() as ConfigBackupException).code)
    }

    @Test
    fun secretNamesOnImportAreInvalidParameters() = runTest {
        val store = storeWithLive()
        val secret = incomingBundle(extras = listOf("magiskToken" to "leak"))
        val result = runCatching { backup(store).import(bundleText = secret.toString(), dryRun = true) }
        assertEquals(FleetError.INVALID_PARAMETERS, (result.exceptionOrNull() as ConfigBackupException).code)
        assertEquals("true", store.getPreference(ConfigPreferenceId.AUTO_CONNECT))
    }

    @Test
    fun wrongPackageIsNotFound() = runTest {
        val result = runCatching {
            backup().import(bundleText = incomingBundle(packageName = "ai.other.app").toString(), dryRun = true)
        }
        assertEquals(FleetError.NOT_FOUND, (result.exceptionOrNull() as ConfigBackupException).code)
    }

    @Test
    fun unknownConflictPolicyIsInvalidParameters() = runTest {
        val result = runCatching {
            backup().import(bundleText = incomingBundle().toString(), dryRun = true, conflictPolicy = "clobber")
        }
        assertEquals(FleetError.INVALID_PARAMETERS, (result.exceptionOrNull() as ConfigBackupException).code)
    }

    @Test
    fun forbiddenNameDetectorCoversTokensAndPasswords() {
        assertTrue(ConfigSecrets.isForbiddenName("magiskToken"))
        assertTrue(ConfigSecrets.isForbiddenName("api_token"))
        assertTrue(ConfigSecrets.isForbiddenName("rootPassword"))
        assertTrue(ConfigSecrets.isForbiddenName("pairingKey"))
        assertTrue(ConfigSecrets.isForbiddenName("keystorePassword"))
        assertTrue(ConfigSecrets.isForbiddenName("KernelSU_token"))
        assertTrue(ConfigSecrets.isForbiddenName("APatchToken"))
        assertFalse(ConfigSecrets.isForbiddenName("autoConnect"))
        assertFalse(ConfigSecrets.isForbiddenName("terminalApp"))
        assertFalse(ConfigSecrets.isForbiddenName("schemaVersion"))
    }

}
