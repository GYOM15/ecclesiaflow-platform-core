package com.ecclesiaflow.platform.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the module's OpenAPI contract lives, bound to {@code ecclesiaflow.contract.*}. The default finds the
 * files every module keeps under {@code src/main/resources/api/}; a module whose contract lives elsewhere, or
 * spans files outside that folder, lists them. Every location must match at least one file.
 */
@ConfigurationProperties(prefix = ApiContractProperties.PREFIX)
public class ApiContractProperties {

    public static final String PREFIX = "ecclesiaflow.contract";

    public static final String DEFAULT_LOCATION = "classpath*:api/*.yaml";

    /** Resource locations or patterns, e.g. {@code classpath:api/church.yaml}. */
    private List<String> locations = new ArrayList<>(List.of(DEFAULT_LOCATION));

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations;
    }
}
