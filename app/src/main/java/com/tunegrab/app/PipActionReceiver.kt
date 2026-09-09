package com.tunegrab.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alvo dos BOTÕES DA JANELINHA PiP (RemoteAction, v0.18.3): play/pause SEM
 * expandir o vídeo — igual YouTube Premium. Receiver declarado no manifesto
 * (exported=false) e alvo de PendingIntent EXPLÍCITO: mais confiável que
 * receptor dinâmico (que tem pegadinhas de broadcast implícito no Android
 * 13/14). Só repassa pro gancho da PlayerActivity — mesmo processo.
 */
class PipActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_TOGGLE) {
            PlayerActivity.dispatchPipToggle()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.tunegrab.app.pip.TOGGLE"
    }
}
