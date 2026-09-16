package com.manliao.backend.notifications;
import jakarta.validation.constraints.Pattern;
public record NotificationPreferencesUpdate(
 Boolean chat_messages_enabled, Boolean friend_requests_enabled, Boolean matches_enabled,
 Boolean social_notifications_enabled, Boolean group_messages_enabled, Boolean live_notifications_enabled,
 Boolean official_notifications_enabled, Boolean payment_notifications_enabled, Boolean night_quiet_enabled,
 @Pattern(regexp="[0-9]{2}:[0-9]{2}") String night_quiet_start,
 @Pattern(regexp="[0-9]{2}:[0-9]{2}") String night_quiet_end) {}
