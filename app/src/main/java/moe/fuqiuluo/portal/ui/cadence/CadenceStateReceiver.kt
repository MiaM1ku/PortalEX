package moe.fuqiuluo.portal.ui.cadence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CadenceStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CadenceBroadcasts.ACTION_REQUEST_STATE) {
            CadenceBroadcasts.broadcastCurrentState(context, silent = true)
        }
    }
}
