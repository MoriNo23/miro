package com.miro.a11y

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * RecentsOverviewActivity — custom fullscreen "Recents" UI for the
 * OLAX Magic Q1 (v1.4.22+).
 *
 * Background (2026-09-01): the stock Recents screen on OLAX is
 * throttled by Quickstep's OverviewCommandHelper — calling
 * performGlobalAction(GLOBAL_ACTION_RECENTS) is silently dropped if
 * the user (or a previous bug) invoked it too recently. There is no
 * exported RecentsActivity we can launch directly (the system's
 * RecentsActivity is not exported and refuses am start with
 * SecurityException). The only way to give the user a usable
 * "recent tasks" experience is to render our own list and
 * killBackgroundProcesses on tap.
 *
 * What this activity does:
 *  1. Reads ActivityManager.getRunningTasks() (a partial list of
 *     foreground + visible tasks — Google deprecated the broader
 *     API on API 21+ but this method is still functional for
 *     the use case here).
 *  2. Renders one row per task with the app icon, label, package
 *     name, and a "Cerrar" button.
 *  3. Tapping a "Cerrar" button invokes the
 *     AccessibilityService's killPackage() method which calls
 *     ActivityManager.killBackgroundProcesses(packageName).
 *  4. Tapping "Cerrar todas" at the top invokes killAllPackages()
 *     for every task in the list.
 *  5. Tapping "X" (top right) just closes the activity.
 *
 * The activity is `exported=false` in the manifest because it is
 * only ever reached through RecentsActionActivity, which validates
 * the package on the way in.
 */
class RecentsOverviewActivity : Activity() {
    companion object {
        private const val TAG = "miro"
    }

    private lateinit var container: LinearLayout
    private lateinit var lblCount: TextView
    private lateinit var lblEmpty: TextView
    private lateinit var scroll: ScrollView
    private val rows = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recents_overview)

        container = findViewById(R.id.recyclerRecents)
        lblCount = findViewById(R.id.lblCount)
        lblEmpty = findViewById(R.id.lblEmpty)
        scroll = container.parent as ScrollView

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnCloseAll).setOnClickListener { onCloseAll() }

        loadTasks()
    }

    /**
     * Refresh the list (called from onCreate and from a refresh
     * tap if we ever add one).
     */
    private fun loadTasks() {
        val tasks = readRunningTasks()
        val pm = packageManager
        val inflater = LayoutInflater.from(this)

        container.removeAllViews()
        rows.clear()

        if (tasks.isEmpty()) {
            lblCount.text = "0 apps"
            lblEmpty.visibility = View.VISIBLE
            container.visibility = View.GONE
            return
        }
        lblEmpty.visibility = View.GONE
        container.visibility = View.VISIBLE
        lblCount.text = "${tasks.size} apps corriendo"

        for (task in tasks) {
            val pkg = task.topActivity?.packageName ?: continue
            if (pkg == packageName) continue  // skip ourselves
            val appInfo = try {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                continue
            }
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon: Drawable = pm.getApplicationIcon(appInfo)
            val row = inflater.inflate(R.layout.item_recents_row, container, false)
            val imgIcon = row.findViewById<ImageView>(R.id.imgIcon)
            val lblLabel = row.findViewById<TextView>(R.id.lblLabel)
            val lblPackage = row.findViewById<TextView>(R.id.lblPackage)
            val btnKill = row.findViewById<Button>(R.id.btnKill)

            imgIcon.setImageDrawable(icon)
            lblLabel.text = label
            lblPackage.text = pkg
            btnKill.setOnClickListener { onKillOne(pkg, label, row) }

            container.addView(row)
            rows.add(row)
        }
    }

    private fun readRunningTasks(): List<ActivityManager.RecentTaskInfo> {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // maxNum=50 is fine for our use case; we only need the visible
        // list, not a full process inventory.
        @Suppress("DEPRECATION")
        return try {
            am.getRecentTasks(50, ActivityManager.RECENT_WITH_EXCLUDED)
        } catch (e: Exception) {
            Log.w(TAG, "recents overview: getRecentTasks failed: ${e.message}")
            emptyList()
        }
    }

    private fun onKillOne(pkg: String, label: String, row: View) {
        val ok = MiroAccessibilityService.killPackageStatic(pkg)
        if (ok) {
            // Remove the row from the visible list immediately so the
            // user sees the kill take effect.
            container.removeView(row)
            rows.remove(row)
            lblCount.text = "${rows.size} apps corriendo"
            if (rows.isEmpty()) {
                lblEmpty.visibility = View.VISIBLE
                container.visibility = View.GONE
            }
            Toast.makeText(this, "Cerrado: $label", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No se pudo cerrar: $label (puede estar en foreground)", Toast.LENGTH_LONG).show()
        }
    }

    private fun onCloseAll() {
        var killed = 0
        var failed = 0
        for (task in readRunningTasks()) {
            val pkg = task.topActivity?.packageName ?: continue
            if (pkg == packageName) continue
            if (MiroAccessibilityService.killPackageStatic(pkg)) {
                killed++
            } else {
                failed++
            }
        }
        Toast.makeText(
            this,
            "Cerradas: $killed${if (failed > 0) " (no se pudieron cerrar: $failed)" else ""}",
            Toast.LENGTH_SHORT
        ).show()
        // Refresh the list.
        loadTasks()
    }
}
