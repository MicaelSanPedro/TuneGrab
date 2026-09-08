package com.tunegrab.app.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Espelho em memória do estado dos downloads, para a Central de Downloads.
 *
 * IMPORTANTE: isto NÃO faz parte da mecânica de download — o DownloadService
 * continua dono da mecânica e apenas AVISA aqui o que já informa nas
 * notificações (mesma fase, mesmo percentual, mesma velocidade). Se ninguém
 * estiver olhando a Central, nada muda: os dados vivem só neste objeto, sem
 * rede, sem disco, sem lógica.
 */
object DownloadBus {

    enum class State { RUNNING, DONE, FAILED, PAUSED, CANCELLED }

    data class Item(
        val fileName: String,
        val title: String,
        val phase: String = "",
        val percent: Int = 0,
        val indeterminate: Boolean = true,
        val state: State = State.RUNNING,
        val detail: String? = null,
        /** Velocidade atual (texto pronto, ex.: "2,3 MB/s") — só quando faz sentido. */
        val speed: String? = null,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    /** Nome do arquivo do download que está RODANDO de fato (fila: os outros esperam). */
    @Volatile
    var activeFileName: String? = null
        private set

    @Synchronized
    fun setActive(fileName: String?) {
        activeFileName = fileName
    }

    @Synchronized
    fun start(title: String, fileName: String) {
        upsert(Item(fileName = fileName, title = title))
    }

    @Synchronized
    fun progress(
        fileName: String,
        phase: String,
        percent: Int,
        indeterminate: Boolean,
        speed: String? = null
    ) {
        mutate(fileName) {
            it.copy(phase = phase, percent = percent, indeterminate = indeterminate, speed = speed)
        }
    }

    @Synchronized
    fun finished(fileName: String) {
        mutate(fileName) {
            it.copy(
                state = State.DONE,
                phase = "",
                percent = 100,
                indeterminate = false,
                speed = null,
                detail = null
            )
        }
    }

    @Synchronized
    fun paused(fileName: String) {
        mutate(fileName) {
            it.copy(
                state = State.PAUSED,
                phase = "",
                indeterminate = false,
                speed = null,
                detail = null
            )
        }
    }

    @Synchronized
    fun cancelled(fileName: String) {
        mutate(fileName) {
            it.copy(
                state = State.CANCELLED,
                phase = "",
                indeterminate = false,
                speed = null,
                detail = null
            )
        }
    }

    @Synchronized
    fun failed(fileName: String, message: String) {
        mutate(fileName) {
            it.copy(state = State.FAILED, phase = "", indeterminate = false, speed = null, detail = message)
        }
    }

    @Synchronized
    fun clearFinished() {
        // itens vivos (rodando/pausado) ficam; só limpa o histórico
        _items.value = _items.value.filter {
            it.state == State.RUNNING || it.state == State.PAUSED
        }
    }

    private fun upsert(item: Item) {
        val list = _items.value.toMutableList()
        val idx = list.indexOfFirst { it.fileName == item.fileName }
        if (idx >= 0) list[idx] = item else list.add(0, item)
        _items.value = list
    }

    private fun mutate(fileName: String, transform: (Item) -> Item) {
        val list = _items.value.toMutableList()
        val idx = list.indexOfFirst { it.fileName == fileName }
        if (idx < 0) return
        list[idx] = transform(list[idx]).copy(updatedAt = System.currentTimeMillis())
        _items.value = list
    }
}
