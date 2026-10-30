package io.github.davydgky.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsTest {

    @Test
    fun signInCodesAreSecrets() {
        assertTrue(Secrets.isOneTimeCode("Dein WhatsApp-Code: 482-913"))
        assertTrue(Secrets.isOneTimeCode("123456 is your verification code"))
        assertTrue(Secrets.isOneTimeCode("G-829104 ist dein Google-Bestätigungscode."))
        assertTrue(Secrets.isOneTimeCode("Ihre TAN lautet 4821"))
        assertTrue(Secrets.isOneTimeCode("Your Instagram code is 381 204"))
    }

    @Test
    fun everydayMessagesAreNot() {
        assertFalse(Secrets.isOneTimeCode("Wir treffen uns um 14:20 am Bahnhof"))
        assertFalse(Secrets.isOneTimeCode("Der Code für die Haustür ist der übliche"))
        assertFalse(Secrets.isOneTimeCode("Login klappt seit 2024 nicht mehr"))
        assertFalse(Secrets.isOneTimeCode("Das Passwort-Seminar kostet 1500 €"))
        assertFalse(Secrets.isOneTimeCode("Hast du die PIN für Mama? Ruf mich an"))
    }
}
