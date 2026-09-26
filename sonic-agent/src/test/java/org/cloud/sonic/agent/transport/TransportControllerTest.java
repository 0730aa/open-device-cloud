package org.cloud.sonic.agent.transport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.cloud.sonic.agent.common.maps.OccupyMap;
import org.cloud.sonic.agent.tools.RemoteAccessPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TransportControllerTest {
    private final RoutingDelegate routingDelegate = mock(RoutingDelegate.class);
    private final TransportController controller = new TransportController();

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(controller, "routingDelegate", routingDelegate);
        OccupyMap.uiaPorts.put("serial-t1", 7912);
    }

    @After
    public void tearDown() {
        new RemoteAccessPolicy().setEnabled(false);
        OccupyMap.uiaPorts.clear();
    }

    @Test
    public void proxyIsClosedWhenRemoteAccessIsDisabled() {
        assertEquals(HttpStatus.FORBIDDEN, proxy("/uia/7912/status").getStatusCode());
        verify(routingDelegate, never()).redirect(any(), any(), anyString(), anyString());
    }

    @Test
    public void proxyOnlyReachesUiaPortsOpenedForAnOccupation() {
        new RemoteAccessPolicy().setEnabled(true);

        assertEquals(HttpStatus.FORBIDDEN, proxy("/uia/2375/containers/json").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, proxy("/uia/7912@evil.example/status").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, proxy("/uia/").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, proxy("/uia").getStatusCode());
        verify(routingDelegate, never()).redirect(any(), any(), anyString(), anyString());
    }

    @Test
    public void proxyForwardsToAnOccupiedDevicesUiaServer() {
        new RemoteAccessPolicy().setEnabled(true);
        when(routingDelegate.redirect(any(), any(), anyString(), anyString())).thenReturn(ResponseEntity.ok("{}"));

        assertEquals(HttpStatus.OK, proxy("/uia/7912/session/abc/element").getStatusCode());
        verify(routingDelegate).redirect(any(), any(), org.mockito.ArgumentMatchers.eq("http://localhost:7912/session/abc/element"),
                org.mockito.ArgumentMatchers.eq("/uia/7912/session/abc/element"));
    }

    private ResponseEntity proxy(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return controller.catchAll(request, mock(HttpServletResponse.class));
    }
}
