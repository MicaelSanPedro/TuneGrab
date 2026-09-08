package com.tunegrab.app.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Espelho em memória do estado dos downloads, para a Central de Downloads.
 *
 * IMPORTANTE: isto NÃO faz parte da mecânica de download — o DownloadService
 * continua 100% intacto e apenas AVISA aqui o que já informa nas notificações
 * (mesma fase, mesmo percentual). Se ninguém estiver olhando a Central, nada
 * muda: os dados vivem só neste objeto, sem rede, sem disco, sem lógica.
 */
object DownloadBus {

    enum class State { RUNNING, DONE, FAILED }

    data class Item(
        val fileName: String,
        val title: String,
        val phase: String = "",
        val percent: Int = 0,
        val indeterminate: Boolean = true,
        val state: State = State.RUNNING,
        val detail: String? = null,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    @Synchronized
    fun start(title: String, fileName: String) {
        upsert(Item(fileName = fileName, title = title))
    }

    @Synchronized
    fun progress(fileName: String, phase: String, percent: Int, indeterminate: Boolean) {
        mutate(fileName) {
            it.copy(phase = phase, percent = percent, indeterminate = indeterminate)
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
                detail = null
            )
        }
    }

    @Synchronized
    fun failed(fileName: String, message: String) {
        mutate(fileName) {
            it.copy(state = State.FAILED, phase = "", indeterminate = false, detail = message)
        }
    }

    @Synchronized
    fun clearFinished() {
        _items.value = _items.value.filter { it.state == State.RUNNING }
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
