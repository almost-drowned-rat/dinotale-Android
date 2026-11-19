package io.github.almostdrownedrat.dinotale;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Neustart des Spiels in einem frischen Prozess (Handheld-Fork).
 *
 * Läuft im eigenen Prozess ":restart" (Manifest). MainActivity startet sie,
 * solange sie selbst noch im Vordergrund ist, und beendet sich dann
 * (onDestroy -> killProcess). Diese Activity wartet, bis der alte Prozess
 * weg ist, und öffnet die App neu.
 *
 * Vorher wurde die Wiederöffnung per AlarmManager geplant; ab Android 10
 * blockiert das System diesen Start aus dem Hintergrund ("Abort background
 * activity starts"), die App blieb nach dem Laden der ROM geschlossen. Eine
 * sichtbare (durchsichtige) Activity darf die App dagegen starten.
 */
public class RestartActivity extends Activity {

    private static final String TAG = "DinoTale";
    static final String EXTRA_OLD_PID = "old_pid";

    private static final long POLL_MS = 50;
    private static final long TIMEOUT_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int oldPid;
    private long deadline;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        oldPid = getIntent().getIntExtra(EXTRA_OLD_PID, -1);
        deadline = System.currentTimeMillis() + TIMEOUT_MS;
        handler.post(this::waitAndRelaunch);
    }

    /** Whether the old game process still runs. /proc/<pid> of another process is not visible to an app on
     *  Android 10+ (the check was always "gone" and the app reopened inside the dying process), so ask the
     *  ActivityManager, which lists the app's own processes. */
    private boolean processAlive(int pid) {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> ps = am != null ? am.getRunningAppProcesses() : null;
        if (ps == null) return false;
        for (android.app.ActivityManager.RunningAppProcessInfo p : ps) if (p.pid == pid) return true;
        return false;
    }

    private void waitAndRelaunch() {
        boolean oldAlive = oldPid > 0 && processAlive(oldPid);
        if (oldAlive && System.currentTimeMillis() < deadline) {
            handler.postDelayed(this::waitAndRelaunch, POLL_MS);
            return;
        }
        if (oldAlive) {
            Log.w(TAG, "RestartActivity: alter Prozess " + oldPid + " lebt noch, starte trotzdem");
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
            Log.i(TAG, "RestartActivity: App neu gestartet");
        } else {
            Log.w(TAG, "RestartActivity: kein Launch-Intent — bitte manuell öffnen");
        }
        finish();
        overridePendingTransition(0, 0);
        // Der Hilfsprozess wird nicht mehr gebraucht.
        handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
    }
}
