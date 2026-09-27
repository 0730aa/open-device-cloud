package org.cloud.sonic.controller.tools;

import org.cloud.sonic.controller.models.domain.ConfList;
import org.cloud.sonic.controller.services.ConfListService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteTicketKeysTest {

    @Test
    void generatesAndStoresAKeyPairOnFirstUse() {
        ConfListService confList = mock(ConfListService.class);
        ConfList[] stored = {null};
        when(confList.searchByKey(RemoteTicketKeys.CONF_KEY)).thenAnswer(i -> stored[0]);
        org.mockito.Mockito.doAnswer(i -> stored[0] = new ConfList().setConfKey(i.getArgument(0))
                        .setContent(i.getArgument(1)).setExtra(i.getArgument(2)))
                .when(confList).save(eq(RemoteTicketKeys.CONF_KEY), anyString(), anyString());

        RemoteTicketKeys keys = keysWith(confList, "", "");
        String encoded = keys.encodedPublicKey();

        assertEquals(stored[0].getExtra(), encoded);
        RemoteTicketKeys again = keysWith(confList, "", "");
        assertEquals(encoded, again.encodedPublicKey());
        verify(confList, org.mockito.Mockito.times(1)).save(anyString(), anyString(), anyString());
    }

    @Test
    void configuredKeysWinOverTheStoredOnes() {
        ConfListService confList = mock(ConfListService.class);
        KeyPair pair = RemoteTicketKeys.generate();

        RemoteTicketKeys keys = keysWith(confList, encode(pair.getPrivate().getEncoded()), encode(pair.getPublic().getEncoded()));

        assertArrayEquals(pair.getPublic().getEncoded(), keys.publicKey().getEncoded());
        verify(confList, never()).searchByKey(any());
    }

    @Test
    void refusesHalfAConfigurationOrAMismatchedPair() {
        KeyPair pair = RemoteTicketKeys.generate();
        KeyPair other = RemoteTicketKeys.generate();
        ConfListService confList = mock(ConfListService.class);

        assertThrows(IllegalStateException.class,
                () -> keysWith(confList, encode(pair.getPrivate().getEncoded()), "").publicKey());
        assertThrows(IllegalStateException.class,
                () -> keysWith(confList, encode(pair.getPrivate().getEncoded()), encode(other.getPublic().getEncoded())).publicKey());
        assertThrows(IllegalStateException.class,
                () -> RemoteTicketKeys.checkPair((ECPublicKey) other.getPublic(), (ECPrivateKey) pair.getPrivate()));
        assertThrows(IllegalStateException.class, () -> RemoteTicketKeys.decodePublic("not base64 !"));
    }

    private static RemoteTicketKeys keysWith(ConfListService confList, String privateKey, String publicKey) {
        RemoteTicketKeys keys = new RemoteTicketKeys();
        ReflectionTestUtils.setField(keys, "confListService", confList);
        ReflectionTestUtils.setField(keys, "configuredPrivateKey", privateKey);
        ReflectionTestUtils.setField(keys, "configuredPublicKey", publicKey);
        return keys;
    }

    private static String encode(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }
}
