package com.monostr.app.ui.media

import android.content.pm.ActivityInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RotationHoldsTest {
    private class Fake(override var orientation: Int) : OrientationTarget {
        override val key: Any = this
    }

    @Test
    fun `the viewer leaving after the player opened keeps rotation, the last one out restores the old value`() {
        val activity = Fake(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        RotationHolds.acquire(activity) // media viewer
        RotationHolds.acquire(activity) // video player, composed before the viewer is disposed
        RotationHolds.release(activity) // the viewer goes
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.orientation)
        RotationHolds.release(activity) // the player goes
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.orientation)
    }

    @Test
    fun `what was asked for before comes back, not always portrait`() {
        val tablet = Fake(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        RotationHolds.acquire(tablet)
        RotationHolds.release(tablet)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, tablet.orientation)
        RotationHolds.release(tablet) // an extra release changes nothing
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, tablet.orientation)
    }
}
