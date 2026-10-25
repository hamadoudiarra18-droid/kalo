package com.kalo.calendrier;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Rappels d'événements Kalo.
 * La page calcule la liste (2 jours avant + coucher du soleil) ; ici on la programme
 * avec AlarmManager et on affiche les notifications, même quand l'appli est fermée.
 */
public class ReminderReceiver extends BroadcastReceiver {

    static final String PREFS = "kalo";
    static final String KEY = "reminders";
    static final String KEY_COUNT = "reminders_count";
    static final String ACTION_REMIND = "com.kalo.calendrier.REMIND";
    static final String CHANNEL = "kalo_rappels";
    private static final int BASE_CODE = 1000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_REMIND.equals(action)) {
            show(context, intent.getStringExtra("title"), intent.getStringExtra("text"), intent.getIntExtra("code", 0));
        } else {
            // Redémarrage du téléphone ou mise à jour de l'appli : les alarmes sont reprogrammées.
            scheduleAll(context);
        }
    }

    /** Enregistre la liste reçue de la page puis reprogramme toutes les alarmes. */
    static void save(Context context, String json) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply();
        scheduleAll(context);
    }

    private static PendingIntent alarmIntent(Context context, int code, String title, String text) {
        Intent i = new Intent(context, ReminderReceiver.class);
        i.setAction(ACTION_REMIND);
        i.putExtra("title", title);
        i.putExtra("text", text);
        i.putExtra("code", code);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, code, i, flags);
    }

    static void scheduleAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Annule les anciennes alarmes
        int previous = prefs.getInt(KEY_COUNT, 0);
        for (int i = 0; i < previous; i++) am.cancel(alarmIntent(context, BASE_CODE + i, "", ""));

        int count = 0;
        try {
            String json = prefs.getString(KEY, null);
            if (json != null) {
                JSONArray list = new JSONArray(json);
                long now = System.currentTimeMillis();
                boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
                for (int i = 0; i < list.length() && count < 60; i++) {
                    JSONObject r = list.getJSONObject(i);
                    long at = r.optLong("at", 0);
                    if (at <= now) continue;
                    PendingIntent pi = alarmIntent(context, BASE_CODE + count, r.optString("title", "Kalo"), r.optString("text", ""));
                    if (Build.VERSION.SDK_INT >= 23) {
                        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                    } else {
                        am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
                    }
                    count++;
                }
            }
        } catch (Exception ignored) { }
        prefs.edit().putInt(KEY_COUNT, count).apply();
    }

    private static void show(Context context, String title, String text, int code) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Rappels d'événements", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Fêtes islamiques, fêtes du Mali et tes événements");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent tap = PendingIntent.getActivity(context, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_kalo_notif)
                .setContentTitle(title == null ? "Kalo" : title)
                .setContentText(text == null ? "" : text)
                .setStyle(new Notification.BigTextStyle().bigText(text == null ? "" : text))
                .setColor(Color.parseColor("#173f37"))
                .setContentIntent(tap)
                .setAutoCancel(true);
        nm.notify(code, b.build());
    }
}
