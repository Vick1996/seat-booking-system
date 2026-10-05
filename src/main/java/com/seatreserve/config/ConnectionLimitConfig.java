package com.seatreserve.config;

import org.apache.coyote.AbstractProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caps concurrent connections at what the heap can hold, instead of a fixed number that is wrong for
 * every instance size. Measured on this service: a request in flight costs about 250KB of heap
 * (virtual-thread stack, Tomcat and security objects, parsed body), so 1,000 in flight fit a 512MB
 * container but 4,000 run it out of memory, and 20,000 do not fit even 2GB. Over the cap, Tomcat stops
 * accepting; waiting clients sit in the kernel's accept queue, which costs almost nothing, and are
 * served as capacity frees up. The alternative, accepting everything, kills the process (and every
 * request in flight with it) exactly when it is busiest.
 */
@Configuration
public class ConnectionLimitConfig {
    private static final Logger log = LoggerFactory.getLogger(ConnectionLimitConfig.class);

    static final long HEAP_PER_CONNECTION = 256L * 1024;
    static final int MIN_CONNECTIONS = 256;
    static final int MAX_CONNECTIONS = 30_000;

    /** Pure sizing rule. An unset heap limit reports Long.MAX_VALUE, hence the upper clamp. */
    static int fromHeap(long maxHeapBytes) {
        return (int) Math.max(MIN_CONNECTIONS, Math.min(MAX_CONNECTIONS, maxHeapBytes / HEAP_PER_CONNECTION));
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatConnectionLimit(SeatProperties props) {
        boolean configured = props.maxConnections() > 0;
        int limit = configured ? props.maxConnections() : fromHeap(Runtime.getRuntime().maxMemory());
        log.info("tomcat max-connections = {} ({})", limit, configured ? "from SEAT_MAX_CONNECTIONS" : "sized from the heap");
        return factory -> factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol) protocol.setMaxConnections(limit);
        });
    }
}
