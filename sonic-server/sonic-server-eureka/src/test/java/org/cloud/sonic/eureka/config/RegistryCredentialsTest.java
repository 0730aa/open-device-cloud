package org.cloud.sonic.eureka.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryCredentialsTest {

    @Test
    void refusesTheShippedDefaultAndOtherGuessablePasswords() {
        assertThrows(IllegalStateException.class, () -> RegistryCredentials.check("sonic"));
        assertThrows(IllegalStateException.class, () -> RegistryCredentials.check(""));
        assertThrows(IllegalStateException.class, () -> RegistryCredentials.check(null));
        assertThrows(IllegalStateException.class, () -> RegistryCredentials.check("a".repeat(RegistryCredentials.MIN_PASSWORD_LENGTH - 1)));
    }

    @Test
    void refusesPasswordsThatWouldBreakTheRegistryUrl() {
        assertThrows(IllegalStateException.class, () -> RegistryCredentials.check("p@ssword/with:colons"));
        assertThrows(IllegalStateException.class, () -> RegistryCredentials.check("long enough but spaces"));
    }

    @Test
    void acceptsARandomHexPassword() {
        assertDoesNotThrow(() -> RegistryCredentials.check("3f9c2e7a1b4d8f60c5e2a9b7d1f4c3e8a6b0d2f5c7e9a1b3"));
    }
}
