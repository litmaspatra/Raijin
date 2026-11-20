package com.raijin.app.permissions

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the release-scope deletion disablement: file deletion
 * is out of release scope (PRD ST-x is future work). Both layers must fail
 * safely - the ActionExecutor dispatch ("soft_delete" action, including
 * LLM-produced invocations) and the deepest boundary
 * (PermissionBroker.softDelete) - without touching user files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageDeletionDisabledTest {

    @Test
    fun softDeleteActionIsRefusedAndDeletesNothing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val victim = File(context.filesDir, "victim_soft_delete.txt").apply { writeText("keep me") }
        val executor = ActionExecutor(context, CoroutineScope(Dispatchers.IO), PermissionBroker(context))

        val result = executor.execute("soft_delete", mapOf("file_path" to victim.absolutePath))

        assertTrue(
            "expected Failure or Denied, got $result",
            result is ActionExecutor.Result.Failure || result is ActionExecutor.Result.Denied,
        )
        assertTrue("file must not be deleted", victim.exists())
        assertTrue("content must be intact", victim.readText() == "keep me")
        assertFalse(
            "no trash folder may be created",
            File(context.filesDir, "raijin_trash").exists(),
        )
    }

    @Test
    fun brokerSoftDeleteIsDisabledAndMovesNothing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val victim = File(context.filesDir, "victim_broker.txt").apply { writeText("keep me") }

        val dest = PermissionBroker(context).softDelete(victim)

        assertNull("disabled softDelete returns null", dest)
        assertTrue("file must not be moved", victim.exists())
        assertTrue("content must be intact", victim.readText() == "keep me")
        assertFalse(
            "no recovery folder may be created",
            File(context.filesDir, ".raijin_recovery").exists(),
        )
    }
}
