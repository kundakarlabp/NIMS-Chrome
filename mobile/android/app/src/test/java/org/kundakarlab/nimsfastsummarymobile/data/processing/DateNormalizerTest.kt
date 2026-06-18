package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.*
import org.junit.Test

class DateNormalizerTest {
    @Test fun ordersKnownFormatsChronologically() {
        assertTrue(DateNormalizer.normalize("31-05-2026").sortEpoch!! < DateNormalizer.normalize("02-06-2026").sortEpoch!!)
        assertTrue(DateNormalizer.normalize("31/05/2026").sortEpoch!! < DateNormalizer.normalize("02/06/2026").sortEpoch!!)
        assertNotNull(DateNormalizer.normalize("02/06/2026 07:30 PM").sortEpoch)
        assertNotNull(DateNormalizer.normalize("2026-06-02T10:15:30Z").sortEpoch)
    }
    @Test fun unknownDateHasNoSortEpoch() { assertNull(DateNormalizer.normalize("not a date").sortEpoch) }
}
