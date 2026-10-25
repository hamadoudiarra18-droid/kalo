package com.kalo.calendrier;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Widget d'écran d'accueil : jour lunaire, mois, phase de la lune.
 * Il change de jour au coucher du soleil, avec les données préparées par l'appli
 * (70 jours à l'avance, mis à jour à chaque ouverture de Kalo).
 */
public class KaloWidget extends AppWidgetProvider {

    static final String PREFS = "kalo";
    static final String KEY = "widget";
    static final String ACTION_TICK = "com.kalo.calendrier.TICK";
    private static final TimeZone MALI = TimeZone.getTimeZone("Africa/Bamako");

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, build(context));
        scheduleNext(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_TICK.equals(intent.getAction())) refreshAll(context);
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(tickIntent(context));
    }

    /** Redessine tous les widgets Kalo posés sur l'écran d'accueil. */
    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, KaloWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews views = build(context);
        for (int id : ids) manager.updateAppWidget(id, views);
        scheduleNext(context);
    }

    private static String todayIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(MALI);
        return f.format(new Date());
    }

    /** Index du jour à afficher : aujourd'hui, ou demain si le soleil est déjà couché. */
    private static int currentIndex(JSONArray days) throws Exception {
        String today = todayIso();
        long now = System.currentTimeMillis();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.getJSONObject(i);
            if (today.equals(d.getString("date"))) {
                long sunset = d.optLong("sunset", 0);
                if (sunset > 0 && now >= sunset && i + 1 < days.length()) return i + 1;
                return i;
            }
        }
        return -1;
    }

    static RemoteViews build(Context context) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_kalo);

        Intent open = new Intent(context, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 0, open, flags));

        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        try {
            if (json == null) throw new Exception("vide");
            JSONObject data = new JSONObject(json);
            JSONArray days = data.getJSONArray("days");
            int i = currentIndex(days);
            if (i < 0) throw new Exception("périmé");
            JSONObject d = days.getJSONObject(i);
            int day = d.getInt("day");
            boolean evening = !todayIso().equals(d.getString("date"));

            v.setTextViewText(R.id.w_day, String.valueOf(day));
            v.setTextViewText(R.id.w_suffix, day == 1 ? "er jour" : "e jour");
            v.setTextViewText(R.id.w_month, d.getString("month"));
            v.setTextViewText(R.id.w_second, d.optString("second", "") + " · " + d.optInt("year") + " H"
                    + (d.optBoolean("confirmed") ? " · confirmé" : " · estimé"));
            v.setTextViewText(R.id.w_info, d.optString("phase", "") + " · " + data.optString("place", "")
                    + (evening ? " · soir" : ""));
        } catch (Exception e) {
            v.setTextViewText(R.id.w_day, "—");
            v.setTextViewText(R.id.w_suffix, "");
            v.setTextViewText(R.id.w_month, "Kalo");
            v.setTextViewText(R.id.w_second, "Ouvre l'appli pour mettre le widget à jour");
            v.setTextViewText(R.id.w_info, "Calendrier lunaire");
        }
        return v;
    }

    private static PendingIntent tickIntent(Context context) {
        Intent i = new Intent(context, KaloWidget.class);
        i.setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, 1, i, flags);
    }

    /** Prochain réveil : coucher du soleil d'aujourd'hui, sinon minuit (heure du Mali). */
    static void scheduleNext(Context context) {
        long now = System.currentTimeMillis();
        Calendar midnight = Calendar.getInstance(MALI);
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 30);
        long next = midnight.getTimeInMillis();
        try {
            String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
            if (json != null) {
                JSONArray days = new JSONObject(json).getJSONArray("days");
                String today = todayIso();
                for (int i = 0; i < days.length(); i++) {
                    JSONObject d = days.getJSONObject(i);
                    if (today.equals(d.getString("date"))) {
                        long sunset = d.optLong("sunset", 0);
                        if (sunset > now && sunset < next) next = sunset + 30000;
                        break;
                    }
                }
            }
        } catch (Exception ignored) { }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = tickIntent(context);
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC, next, pi);
        else am.set(AlarmManager.RTC, next, pi);
    }
}
