package com.eds.overlay.location

/**
 * Last known vehicle speed in km/h.
 *
 * OverlayService writes this on each GPS fix. Ambient UI (glow orbs)
 * reads it from an already-running 20 fps loop — a volatile float,
 * no listeners, no allocations, no extra location requests.
 */
object LiveSpeed {
    @Volatile
    var kmh: Float = 0f
}
