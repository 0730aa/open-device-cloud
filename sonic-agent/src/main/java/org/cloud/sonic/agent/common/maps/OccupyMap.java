package org.cloud.sonic.agent.common.maps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * status
 */
public class OccupyMap {
    public static Map<String, ScheduledFuture<?>> map = new ConcurrentHashMap<>();
    /**
     * Device serial -> user who occupied it, whose device claim is released with the occupation.
     */
    public static Map<String, String> users = new ConcurrentHashMap<>();
}
