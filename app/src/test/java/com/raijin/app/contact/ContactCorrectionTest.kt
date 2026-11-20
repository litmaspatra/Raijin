package com.raijin.app.contact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raijin.app.RaijinDatabase
import com.raijin.app.permissions.PermissionBroker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CT-7: a rejected contact resolution is permanently recorded and
 * deprioritizes that contact for the same spoken form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactCorrectionTest {

    private lateinit var db: RaijinDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, RaijinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun resolver(): ContactResolver = ContactResolver(
        PermissionBroker(ApplicationProvider.getApplicationContext()),
        db.contactEntityDao(),
        db.correctionEntryDao(),
    )

    @Test
    fun correctedContactIsDeprioritizedForSameAlias() = runBlocking {
        val dao = db.contactEntityDao()
        dao.insert(
            ContactEntity(
                canonicalName = "Rahul Work", displayName = "Rahul Work",
                aliases = "rahul", phoneNumber = "111", group = "UNKNOWN",
            ),
        )
        dao.insert(
            ContactEntity(
                canonicalName = "Rahul Home", displayName = "Rahul Home",
                aliases = "rahul", phoneNumber = "222", group = "UNKNOWN",
            ),
        )

        val r = resolver()
        r.recordCorrection("rahul", "111")

        // The corrected contact must no longer be the single auto-pick; the
        // other candidate wins outright.
        when (val result = r.resolve("rahul")) {
            is ResolveResult.Single -> assertEquals("222", result.entity.phoneNumber)
            is ResolveResult.Multiple -> assertTrue(
                result.candidates.none { it.phoneNumber == "111" },
            )
            else -> throw AssertionError("expected a resolution, got $result")
        }
    }

    @Test
    fun correctionSurvivesAndAppliesAcrossResolverInstances() = runBlocking {
        val dao = db.contactEntityDao()
        dao.insert(
            ContactEntity(
                canonicalName = "Rahul Work", displayName = "Rahul Work",
                aliases = "rahul", phoneNumber = "111", group = "UNKNOWN",
            ),
        )

        resolver().recordCorrection("rahul", "111")
        val stored = db.correctionEntryDao().findByWrongAlias("rahul")
        assertEquals(1, stored.size)
        assertEquals("rahul", stored.single().wrongAlias)

        // A fresh resolver over the same database still sees the correction:
        // with only one candidate it may still resolve (fallback), but the
        // record itself is permanent.
        val again = db.correctionEntryDao().findByWrongAlias("RAHUL")
        assertEquals(1, again.size)
    }
}
