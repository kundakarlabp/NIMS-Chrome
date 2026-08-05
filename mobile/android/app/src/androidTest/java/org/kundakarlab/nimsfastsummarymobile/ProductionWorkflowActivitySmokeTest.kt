package org.kundakarlab.nimsfastsummarymobile

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionWorkflowActivitySmokeTest {
    @Test
    fun productionLauncherCreatesWithoutImmediateCrash() {
        ActivityScenario.launch(ProductionWorkflowActivity::class.java).use { scenario ->
            var observed = false
            scenario.onActivity { activity ->
                observed = true
                assertFalse(activity.isFinishing)
            }
            assertTrue(observed)
        }
    }
}
