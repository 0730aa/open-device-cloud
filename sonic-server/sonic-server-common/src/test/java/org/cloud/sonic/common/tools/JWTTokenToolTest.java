package org.cloud.sonic.common.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JWTTokenToolTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough-0123456789";

    private final JWTTokenTool tool = newTool(SECRET);

    @Test
    void userTokenIsVerifiedAndCarriesTheUserName() {
        String token = tool.getToken("alice");

        assertTrue(tool.verify(token));
        assertEquals("alice", tool.getUserName(token));
        assertFalse(tool.verifyInternal(token));
    }

    @Test
    void internalTokenIsNeverAcceptedAsAUserToken() {
        String token = tool.getInternalToken();

        assertTrue(tool.verifyInternal(token));
        assertFalse(tool.verify(token));
        assertNull(tool.getUserName(token));
    }

    @Test
    void userNamedLikeTheInternalScopeGetsNoInternalAccess() {
        assertFalse(tool.verifyInternal(tool.getToken("sonic-internal")));
    }

    @Test
    void internalTokenFromAnotherSecretIsRejected() {
        String forged = newTool("some-other-secret-that-is-long-enough-987654321").getInternalToken();

        assertFalse(tool.verifyInternal(forged));
    }

    @Test
    void expiredInternalTokenIsRejected() {
        String expired = JWT.create().withClaim("scope", "sonic-internal")
                .withExpiresAt(new Date(System.currentTimeMillis() - 1_000))
                .sign(Algorithm.HMAC256(SECRET));

        assertFalse(tool.verifyInternal(expired));
    }

    @Test
    void missingOrMalformedInternalTokenIsRejected() {
        assertFalse(tool.verifyInternal(null));
        assertFalse(tool.verifyInternal("not-a-jwt"));
    }

    @Test
    void refusesToStartWithTheOldDefaultOrAShortSecret() {
        assertThrows(IllegalStateException.class, () -> newTool("sonic").checkSecret());
        assertThrows(IllegalStateException.class, () -> newTool("").checkSecret());
        assertThrows(IllegalStateException.class, () -> newTool(null).checkSecret());
        assertThrows(IllegalStateException.class, () -> newTool(" ".repeat(40)).checkSecret());
        assertThrows(IllegalStateException.class, () -> newTool("x".repeat(JWTTokenTool.MIN_SECRET_LENGTH - 1)).checkSecret());
        newTool(SECRET).checkSecret();
    }

    private static JWTTokenTool newTool(String secret) {
        JWTTokenTool tool = new JWTTokenTool();
        ReflectionTestUtils.setField(tool, "TOKEN_SECRET", secret);
        ReflectionTestUtils.setField(tool, "EXPIRE_DAY", 1);
        return tool;
    }
}
