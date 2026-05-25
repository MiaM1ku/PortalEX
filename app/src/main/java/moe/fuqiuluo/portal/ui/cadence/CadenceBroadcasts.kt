package moe.fuqiuluo.portal.ui.cadence

import android.content.Context
import android.content.Intent
import moe.fuqiuluo.portal.ext.cadenceMax
import moe.fuqiuluo.portal.ext.cadenceMin
import moe.fuqiuluo.portal.ext.cadenceMockEnabled

object CadenceBroadcasts {
    const val ACTION_TOGGLE_CADENCE = "moe.fuqiuluo.portal.action.TOGGLE_CADENCE"
    const val ACTION_REQUEST_STATE = "moe.fuqiuluo.portal.action.REQUEST_CADENCE_STATE"
    const val EXTRA_STATE = "STATE"
    const val EXTRA_MIN_CADENCE = "MIN_CADENCE"
    const val EXTRA_MAX_CADENCE = "MAX_CADENCE"
    const val EXTRA_SILENT = "SILENT"

    fun broadcastCurrentState(context: Context, silent: Boolean = false) {
        val appContext = context.applicationContext
        val minCadence = appContext.cadenceMin.coerceIn(140f, 220f)
        val maxCadence = appContext.cadenceMax.coerceIn(140f, 220f)

        appContext.sendBroadcast(Intent(ACTION_TOGGLE_CADENCE).apply {
            putExtra(EXTRA_STATE, appContext.cadenceMockEnabled)
            putExtra(EXTRA_MIN_CADENCE, minCadence)
            putExtra(EXTRA_MAX_CADENCE, maxCadence)
            putExtra(EXTRA_SILENT, silent)
        })
    }
}
