/*
 *   sonic-server  Sonic Cloud Real Machine Platform.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.controller.tools;

import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.controller.models.domain.ConfList;
import org.cloud.sonic.controller.services.ConfListService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The EC P-256 key pair remote tickets are signed with. Only the server holds the private key;
 * agents (and relays) receive the public key, so they can check tickets but never issue one.
 * <p>
 * Taken from sonic.ticket.private-key / public-key when both are set, which multi-instance
 * deployments should do so every controller signs with the same key. Otherwise generated on
 * first use and kept in conf_list.
 */
@Slf4j
@Component
public class RemoteTicketKeys {
    static final String CONF_KEY = "remote-ticket-key";

    @Autowired
    private ConfListService confListService;
    @Value("${sonic.ticket.private-key:}")
    private String configuredPrivateKey;
    @Value("${sonic.ticket.public-key:}")
    private String configuredPublicKey;

    private volatile ECPublicKey publicKey;
    private volatile ECPrivateKey privateKey;

    public RemoteTicketKeys() {
    }

    public RemoteTicketKeys(ECPublicKey publicKey, ECPrivateKey privateKey) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public ECPublicKey publicKey() {
        load();
        return publicKey;
    }

    public ECPrivateKey privateKey() {
        load();
        return privateKey;
    }

    /**
     * The public key as agents receive it: base64 of its X.509 SubjectPublicKeyInfo encoding.
     */
    public String encodedPublicKey() {
        return Base64.getEncoder().encodeToString(publicKey().getEncoded());
    }

    private void load() {
        if (publicKey != null && privateKey != null) {
            return;
        }
        synchronized (this) {
            if (publicKey != null && privateKey != null) {
                return;
            }
            String encodedPrivate = configuredPrivateKey;
            String encodedPublic = configuredPublicKey;
            if (isBlank(encodedPrivate) != isBlank(encodedPublic)) {
                throw new IllegalStateException("Set both sonic.ticket.private-key and sonic.ticket.public-key, or neither.");
            }
            if (isBlank(encodedPrivate)) {
                ConfList stored = confListService.searchByKey(CONF_KEY);
                if (stored == null) {
                    KeyPair generated = generate();
                    confListService.save(CONF_KEY, encode(generated.getPrivate().getEncoded()),
                            encode(generated.getPublic().getEncoded()));
                    // Read back: if another instance saved its key at the same time, use the stored one.
                    stored = confListService.searchByKey(CONF_KEY);
                    log.info("Generated the remote ticket signing key.");
                }
                encodedPrivate = stored.getContent();
                encodedPublic = stored.getExtra();
            }
            ECPrivateKey loadedPrivate = decodePrivate(encodedPrivate);
            ECPublicKey loadedPublic = decodePublic(encodedPublic);
            checkPair(loadedPublic, loadedPrivate);
            privateKey = loadedPrivate;
            publicKey = loadedPublic;
        }
    }

    /**
     * A mismatched pair would make every ticket fail on the agents, so refuse it up front.
     */
    static void checkPair(ECPublicKey publicKey, ECPrivateKey privateKey) {
        try {
            byte[] probe = "remote-ticket-key-check".getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            if (!verifier.verify(signature)) {
                throw new IllegalStateException("The remote ticket public key does not belong to the private key");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot check the remote ticket key pair", e);
        }
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate an EC P-256 key pair", e);
        }
    }

    public static ECPublicKey decodePublic(String base64) {
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("Invalid remote ticket public key", e);
        }
    }

    public static ECPrivateKey decodePrivate(String base64) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("Invalid remote ticket private key", e);
        }
    }

    private static String encode(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
