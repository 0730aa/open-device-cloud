package org.cloud.sonic.agent.transport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.cloud.sonic.agent.common.maps.OccupyMap;
import org.cloud.sonic.agent.tools.RemoteAccessPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(TransportController.DELEGATE_PREFIX)
public class TransportController {

    public final static String DELEGATE_PREFIX = "/uia";

    @Autowired
    private RoutingDelegate routingDelegate;

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE}, produces = MediaType.ALL_VALUE)
    public ResponseEntity catchAll(HttpServletRequest request, HttpServletResponse response) {
        String uri = request.getRequestURI();
        String target = uri.length() > DELEGATE_PREFIX.length() ? uri.substring(DELEGATE_PREFIX.length() + 1) : "";
        int slash = target.indexOf('/');
        String port = slash < 0 ? target : target.substring(0, slash);
        // Only UIA2 servers opened for a remote occupation, never any other local port or host.
        if (!RemoteAccessPolicy.isEnabled() || !port.matches("\\d{1,5}")
                || !OccupyMap.uiaPorts.containsValue(Integer.parseInt(port))) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        return routingDelegate.redirect(request, response, "http://localhost:" + target, uri);
    }
}