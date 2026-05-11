package com.forge.autophone;

/**
 * Forge AutoPhone — AIDL interface (client-side copy in Forge OS).
 *
 * Forge OS binds to com.forge.autophone and calls these methods to:
 *   a) Control the device UI via accessibility (tap, type, scroll, etc.)
 *   b) Read, dismiss, and reply to status-bar notifications
 *   c) Notify AutoPhone when a schedule plan starts / finishes
 *
 * The implementations live in the Forge AutoPhone app.
 * This file must be kept in sync with IAutoPhoneService.aidl in that project.
 */
interface IAutoPhoneService {

    // ── Screen-control tools ─────────────────────────────────────────────────
    String readScreen();
    String tapByText(String text);
    String tapAt(int x, int y);
    String typeText(String text);
    String swipe(String direction, int amount);
    String scroll(String direction);
    String launchApp(String packageOrLabel);
    String goBack();
    String goHome();
    String openNotifications();
    String screenshot();
    String findAndTap(String text);
    boolean isServiceActive();

    // ── Notification tools ───────────────────────────────────────────────────
    String readNotifications();
    String dismissNotification(String key);
    String replyToNotification(String key, String text);
    boolean isNotificationListenerActive();

    // ── Schedule lifecycle (Forge OS → AutoPhone) ─────────────────────────────
    oneway void notifyScheduleStarted(String scheduleId, String planSummary);
    oneway void notifyScheduleCompleted(String scheduleId, boolean ok, String result);
}
