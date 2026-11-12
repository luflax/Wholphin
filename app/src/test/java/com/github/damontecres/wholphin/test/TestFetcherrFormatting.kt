package com.github.damontecres.wholphin.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.ui.fetcherr.formatAgeHours
import com.github.damontecres.wholphin.ui.fetcherr.formatEta
import com.github.damontecres.wholphin.ui.fetcherr.seriesStatusLabel
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Fetcherr labels that go through string resources, so they can be translated
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TestFetcherrFormatting {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `release ages`() {
        Assert.assertEquals("<1h", resources.formatAgeHours(0.5))
        Assert.assertEquals("30h", resources.formatAgeHours(30.0))
        Assert.assertEquals("3d", resources.formatAgeHours(72.0))
        Assert.assertEquals("2y", resources.formatAgeHours(2.0 * 24 * 365))
    }

    @Test
    fun `time left is shown to the minute`() {
        Assert.assertEquals(resources.formatEta(12 * 60), resources.formatEta(12 * 60 + 5))
        Assert.assertNotEquals(resources.formatEta(45), resources.formatEta(60))
    }

    @Test
    fun `known series statuses are translated and others shown as is`() {
        Assert.assertEquals("Returning series", resources.seriesStatusLabel("Returning Series"))
        Assert.assertEquals("Something new", resources.seriesStatusLabel("Something new"))
    }
}
