package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.*
import org.junit.Test

class NimsCredentialAutofillTest {
    @Test fun fillScriptEscapesCredentialsAndTargetsCaptchaOnlyForFocus() {
        val script = NimsCredentialAutofill.fillScript("dr\"user", "p'ass\\word")
        assertTrue(script.contains("filledPassword"))
        assertTrue(script.contains("captchaFound"))
        assertFalse(script.contains("captchaValue"))
        assertFalse(script.contains("return captcha"))
        assertTrue(script.contains("dr\\\"user"))
    }

    @Test fun authenticateRequiresCaptchaWithoutReturningItsValue() {
        val script = NimsCredentialAutofill.authenticateScript
        assertTrue(script.contains("captcha_required"))
        assertTrue(script.contains("clicked_login"))
        assertFalse(script.contains("captchaValue"))
        assertFalse(script.contains("value:captcha"))
    }
}
