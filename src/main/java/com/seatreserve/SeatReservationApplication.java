package com.seatreserve;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SeatReservationApplication {
    public static void main(String[] args) {
        // The PG driver sends the JVM zone as a startup parameter; legacy aliases like
        // "Asia/Calcutta" are rejected by some servers. Timestamps are timestamptz, so UTC is safe.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SeatReservationApplication.class, args);
    }
}
