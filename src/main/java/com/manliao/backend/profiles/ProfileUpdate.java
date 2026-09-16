package com.manliao.backend.profiles;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
public record ProfileUpdate(
 @Size(max=32) String nickname,
 @JsonProperty("avatar_url") @Size(max=500) String avatarUrl,
 @Min(18) @Max(120) Integer age,
 @Size(max=16) String zodiac, @Size(max=240) String bio, @Size(max=48) String occupation,
 @Size(max=30) List<@NotNull @Size(max=64) String> tags,
 @Size(max=30) List<@NotNull @Size(max=64) String> hobbies,
 @JsonProperty("photo_urls") @Size(max=100) List<@NotBlank @Size(max=500) String> photoUrls,
 @JsonProperty("distance_km") @DecimalMin("0") Double distanceKm,
 @JsonProperty("distance_visible") Boolean distanceVisible) {}
