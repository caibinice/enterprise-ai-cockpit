package com.example.aiagent.parking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Only a bounded, explicitly labelled browser demo snapshot crosses this boundary. */
public record ParkingAgentRequest(
    @NotBlank @Size(max = 2000) String message,
    @Size(max = 100) String model,
    @NotNull @Valid Context context,
    @Size(max = 8) List<@Valid Turn> history
) {
    public record Context(
        @NotBlank @Pattern(regexp = "browser-demo") String source,
        @NotBlank @Size(max = 40) String observedAt,
        boolean sceneReady,
        @NotNull @Size(min = 3, max = 3) List<@Valid Zone> zones,
        @NotNull @Size(max = 30) List<@Valid Entry> events,
        @NotNull @Size(max = 10) List<@Valid Alert> alerts,
        @Size(max = 100) String selected,
        @Size(max = 500) String lastActionResult
    ) {}
    public record Zone(
        @NotBlank @Pattern(regexp = "[ABC]") String id,
        @Min(1) @Max(10000) int capacity,
        @Min(0) @Max(10000) int occupied
    ) {}
    public record Entry(
        @NotBlank @Size(max = 30) String time,
        @NotBlank @Size(max = 30) String plate,
        @NotBlank @Pattern(regexp = "[ABC]") String zone,
        @NotBlank @Pattern(regexp = "入场|离场") String action
    ) {}
    public record Alert(
        @Min(1) @Max(100000) int id,
        @NotBlank @Pattern(regexp = "[ABC]") String zone,
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Pattern(regexp = "高|中|低") String level,
        boolean acknowledged
    ) {}
    public record Turn(
        @NotBlank @Pattern(regexp = "user|assistant") String role,
        @NotBlank @Size(max = 1500) String content
    ) {}
}
