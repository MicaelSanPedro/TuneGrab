package com.tunegrab.app.video

import android.content.Context
import android.util.AttributeSet
import android.view.MeasureSpec
import android.widget.VideoView

/**
 * VideoView que NUNCA deforma o vídeo (v0.10.1).
 *
 * O VideoView padrão, medido com largura fixa (match_parent), preenche a
 * largura toda e, quando a altura correta não cabe no espaço, esmaga a
 * altura sem reduzir a largura — é isso que "amassa" o vídeo em tela cheia
 * em aparelhos modernos (tela ~20:9, vídeo 16:9). Aqui o onMeasure é
 * reescrito: a view se dimensiona como o MAIOR retângulo que cabe no
 * espaço disponível mantendo o aspect ratio real do vídeo (letterbox/
 * pillarbox), em qualquer rotação e em qualquer resolução de tela.
 *
 * O VideoView não expõe o tamanho do vídeo (o método interno é privado),
 * então as dimensões reais vêm do MediaPlayer e entram por
 * [setVideoSize] no momento do onPrepared (feito no PlayerActivity).
 * Antes disso (vídeo ainda carregando) a view preenche o espaço — é só
 * o fundo preto do player.
 */
class FitVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : VideoView(context, attrs) {

    private var videoW = 0
    private var videoH = 0

    /** Dimensões REAIS do vídeo, lidas do MediaPlayer no onPrepared. */
    fun setVideoSize(width: Int, height: Int) {
        videoW = width
        videoH = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (videoW > 0 && videoH > 0 && w > 0 && h > 0) {
            val videoAspect = videoW.toDouble() / videoH.toDouble()
            val boxAspect = w.toDouble() / h.toDouble()
            if (videoAspect > boxAspect) {
                // vídeo mais largo que a caixa: cabe pela LARGURA, sobra altura
                setMeasuredDimension(w, (w / videoAspect).toInt().coerceAtLeast(1))
            } else {
                // vídeo mais alto que a caixa: cabe pela ALTURA, sobra largura
                setMeasuredDimension((h * videoAspect).toInt().coerceAtLeast(1), h)
            }
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
