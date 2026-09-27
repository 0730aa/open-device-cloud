package org.cloud.sonic.relay;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Signs tokens the way sonic-server-controller does.
 */
final class TestTokens {
    private TestTokens() {
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ECPublicKey publicKey(KeyPair pair) {
        return (ECPublicKey) pair.getPublic();
    }

    static String encodedPublicKey(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    static String ticket(KeyPair platform, int agentId, String udId, String user, Duration validFor) {
        return JWT.create()
                .withSubject(user)
                .withClaim(TokenVerifier.AGENT_CLAIM, agentId)
                .withClaim(TokenVerifier.UDID_CLAIM, udId)
                .withExpiresAt(new Date(System.currentTimeMillis() + validFor.toMillis()))
                .withJWTId(UUID.randomUUID().toString())
                .sign(es256(platform));
    }

    static String relayToken(KeyPair platform, int agentId, Duration validFor) {
        return JWT.create()
                .withClaim(TokenVerifier.SCOPE_CLAIM, TokenVerifier.RELAY_AGENT_SCOPE)
                .withClaim(TokenVerifier.AGENT_CLAIM, agentId)
                .withExpiresAt(new Date(System.currentTimeMillis() + validFor.toMillis()))
                .withJWTId(UUID.randomUUID().toString())
                .sign(es256(platform));
    }

    static String hmacRelayToken(String secret, int agentId) {
        return JWT.create()
                .withClaim(TokenVerifier.SCOPE_CLAIM, TokenVerifier.RELAY_AGENT_SCOPE)
                .withClaim(TokenVerifier.AGENT_CLAIM, agentId)
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                .sign(Algorithm.HMAC256(secret));
    }

    private static Algorithm es256(KeyPair pair) {
        return Algorithm.ECDSA256((ECPublicKey) pair.getPublic(), (ECPrivateKey) pair.getPrivate());
    }
}
