package com.minipay.consumerbff.application.service;

import com.minipay.consumerbff.application.error.UpstreamProblemException;
import com.minipay.consumerbff.application.port.ReverseGeocodeGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LocationQueryService {
    private final ReverseGeocodeGateway reverseGeocode;

    public LocationQueryService(ReverseGeocodeGateway reverseGeocode) {
        this.reverseGeocode = reverseGeocode;
    }

    public Mono<String> reverseGeocode(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            return Mono.error(new UpstreamProblemException(
                    HttpStatus.BAD_REQUEST, "LOCATION_COORDINATES_INVALID", "定位坐标无效"));
        }
        return reverseGeocode.resolve(longitude, latitude);
    }
}
