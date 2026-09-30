package com.minipay.consumerbff.interfaces.rest;

import com.minipay.consumerbff.application.service.LocationQueryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {
    private final LocationQueryService locations;

    public LocationController(LocationQueryService locations) {
        this.locations = locations;
    }

    @GetMapping("/reverse-geocode")
    public Mono<Map<String, String>> reverseGeocode(
            @RequestParam double longitude,
            @RequestParam double latitude) {
        return locations.reverseGeocode(longitude, latitude)
                .map(address -> Map.of("formattedAddress", address));
    }
}
