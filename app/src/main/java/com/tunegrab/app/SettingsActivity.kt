package com.tunegrab.app

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.tunegrab.app.databinding.ActivitySettingsBinding

/**
 * Configurações: qualidade padrão de cada formato, configuradas separadamente.
 *  - MP3: bitrate padrão da conversão (320/256/192/128 kbps)
 *  - M4A: melhor disponível ou menor arquivo
 *  - MP4: melhor disponível ou menor arquivo
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        loadCurrent()
        bindMp3()
        bindM4a()
        bindMp4()
    }

    private fun loadCurrent() {
        when (FormatPrefs.mp3DefaultBitrate(this)) {
            256 -> binding.rbMp3256.isChecked = true
            192 -> binding.rbMp3192.isChecked = true
            128 -> binding.rbMp3128.isChecked = true
            else -> binding.rbMp3320.isChecked = true
        }
        binding.rbM4aBest.isChecked = FormatPrefs.m4aPick(this) == FormatPrefs.PICK_BEST
        binding.rbM4aSmall.isChecked = FormatPrefs.m4aPick(this) == FormatPrefs.PICK_SMALL
        binding.rbMp4Best.isChecked = FormatPrefs.mp4Pick(this) == FormatPrefs.PICK_BEST
        binding.rbMp4Small.isChecked = FormatPrefs.mp4Pick(this) == FormatPrefs.PICK_SMALL
    }

    private fun bindMp3() {
        binding.rgMp3.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val kbps = when (checkedId) {
                R.id.rbMp3256 -> 256
                R.id.rbMp3192 -> 192
                R.id.rbMp3128 -> 128
                else -> 320
            }
            FormatPrefs.setMp3DefaultBitrate(this, kbps)
        }
    }

    private fun bindM4a() {
        binding.rgM4a.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val pick = if (checkedId == R.id.rbM4aSmall) FormatPrefs.PICK_SMALL else FormatPrefs.PICK_BEST
            FormatPrefs.setM4aPick(this, pick)
        }
    }

    private fun bindMp4() {
        binding.rgMp4.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val pick = if (checkedId == R.id.rbMp4Small) FormatPrefs.PICK_SMALL else FormatPrefs.PICK_BEST
            FormatPrefs.setMp4Pick(this, pick)
        }
    }
}
