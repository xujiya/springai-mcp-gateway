package es.omarall.mcp.climate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ClimateService {

    private static final String BASE_URL = "https://api.weather.gov";

    private final RestClient restClient;

    public ClimateService() {
        this.restClient = RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/geo+json")
            .defaultHeader("User-Agent", "ClimateMcpServer/1.0 (mcp-gateway@example.com)")
            .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Points(@JsonProperty("properties") Props properties) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Props(@JsonProperty("forecast") String forecast) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Forecast(@JsonProperty("properties") Props property) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Props(@JsonProperty("periods") List<Period> periods) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Period(
                @JsonProperty("number") Integer number,
                @JsonProperty("name") String name,
                @JsonProperty("startTime") String startTime,
                @JsonProperty("endTime") String endTime,
                @JsonProperty("isDaytime") Boolean isDayTime,
                @JsonProperty("temperature") Integer temperature,
                @JsonProperty("temperatureUnit") String temperatureUnit,
                @JsonProperty("temperatureTrend") String temperatureTrend,
                @JsonProperty("probabilityOfPrecipitation") Map probabilityOfPrecipitation,
                @JsonProperty("windSpeed") String windSpeed,
                @JsonProperty("windDirection") String windDirection,
                @JsonProperty("icon") String icon,
                @JsonProperty("shortForecast") String shortForecast,
                @JsonProperty("detailedForecast") String detailedForecast) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(@JsonProperty("features") List<Feature> features) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Feature(@JsonProperty("properties") Properties properties) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Properties(
                @JsonProperty("event") String event,
                @JsonProperty("areaDesc") String areaDesc,
                @JsonProperty("severity") String severity,
                @JsonProperty("description") String description,
                @JsonProperty("instruction") String instruction) {}
    }

    @McpTool(name = "getStormWarnings", description = "Get active storm/climate warnings for a US state. " +
            "Input is a two-letter US state code (e.g. CA, NY, WA)")
    public String getStormWarnings(
            @McpToolParam(description = "Two-letter US state code, e.g. CA, NY") String state) {

        var alert = restClient.get()
            .uri("/alerts/active/area/{state}", state)
            .retrieve()
            .body(Alert.class);

        if (alert == null || alert.features() == null || alert.features().isEmpty()) {
            return "No active warnings for " + state;
        }

        return alert.features().stream()
            .map(f -> String.format("""
                ⚠️ %s
                Area: %s
                Severity: %s
                Description: %s
                Instructions: %s
                """, f.properties().event(), f.properties().areaDesc(),
                    f.properties().severity(), f.properties().description(),
                    f.properties().instruction()))
            .collect(Collectors.joining("\n"));
    }

    @McpTool(name = "getClimateForecast", description = "Get climate forecast for a specific latitude/longitude. " +
            "Example: latitude=34.0522, longitude=-118.2437 for Los Angeles")
    public String getClimateForecast(
            @McpToolParam(description = "Latitude, e.g. 34.0522") double latitude,
            @McpToolParam(description = "Longitude, e.g. -118.2437") double longitude) {

        var points = restClient.get()
            .uri("/points/{latitude},{longitude}", latitude, longitude)
            .retrieve()
            .body(Points.class);

        if (points == null || points.properties() == null || points.properties().forecast() == null) {
            return "No forecast data available for the given location.";
        }

        var forecast = restClient.get()
            .uri(points.properties().forecast())
            .retrieve()
            .body(Forecast.class);

        if (forecast == null || forecast.property() == null) {
            return "No forecast data available.";
        }

        return forecast.property().periods().stream()
            .limit(3)
            .map(p -> String.format("""
                🌤️ %s: %s %s, Wind %s %s — %s
                """, p.name(), p.temperature(), p.temperatureUnit(),
                    p.windSpeed(), p.windDirection(), p.shortForecast()))
            .collect(Collectors.joining());
    }
}
