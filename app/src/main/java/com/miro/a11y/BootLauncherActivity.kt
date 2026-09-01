package com.miro.a11y

import android.os.Bundle

/**
 * BootLauncherActivity — alias launcher declarado en AndroidManifest como un
 * HOME intent-filter adicional.
 *
 * Purpose: the OLAX/Allwinner ROM can only auto-launch the *first* HOME
 * activity it finds after boot. When that is MiroLauncherActivity (already
 * set via `pm set-home-activity`), BootLauncherActivity is a no-op thin
 * wrapper that simply forwards to it, so both names resolve to the same
 * post-boot a11y re-enable flow.
 *
 * Kept for forward-compat / naming clarity (handoff 2026-09-01-fusion-wireless).
 * The real toggle logic lives in MiroLauncherActivity.reenableAccessibility().
 */
class BootLauncherActivity : MiroLauncherActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Forward immediately — no extra logic. The parent handles toggle + handoff.
    }
}
