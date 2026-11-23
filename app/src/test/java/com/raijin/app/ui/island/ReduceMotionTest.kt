package com.raijin.app.ui.island

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI-2: Reduce-Motion detection follows the system animator scale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReduceMotionTest {

    private fun setAnimatorScale(value: Float) {
        Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            value,
        )
    }

    @Test
    fun `animator scale zero means reduce motion`() {
        setAnimatorScale(0f)
        assertTrue(ReduceMotion.isEnabled(ApplicationProvider.getApplicationContext()))
    }

    @Test
    fun `normal animator scale means motion allowed`() {
        setAnimatorScale(1f)
        assertFalse(ReduceMotion.isEnabled(ApplicationProvider.getApplicationContext()))
    }

    @Test
    fun `unset scale defaults to motion allowed`() {
        val cr = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.Global.putString(cr, Settings.Global.ANIMATOR_DURATION_SCALE, null)
        assertFalse(ReduceMotion.isEnabled(ApplicationProvider.getApplicationContext()))
    }
}
