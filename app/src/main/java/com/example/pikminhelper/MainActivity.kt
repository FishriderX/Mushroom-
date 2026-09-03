package com.example.pikminhelper

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.example.pikminhelper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: HelperPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = HelperPrefs(this)

        b.masterSwitch.isChecked = prefs.enabled
        when (prefs.mode) {
            RunMode.ECO -> b.modeEco.isChecked = true
            RunMode.WATCH -> b.modeWatch.isChecked = true
            RunMode.RACE -> b.modeRace.isChecked = true
        }

        b.pauseLowBattery.isChecked = prefs.pauseLowBattery
        b.pauseWhenUserActive.isChecked = prefs.pauseWhenUserActive
        b.blockPaidTicket.isChecked = prefs.blockPaidTicket

        b.masterSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            renderStatus()
        }

        b.modeGroup.setOnCheckedChangeListener { _, id ->
            prefs.mode = when (id) {
                b.modeRace.id -> RunMode.RACE
                b.modeWatch.id -> RunMode.WATCH
                else -> RunMode.ECO
            }
            renderStatus()
        }

        b.pauseLowBattery.setOnCheckedChangeListener { _, v -> prefs.pauseLowBattery = v }
        b.pauseWhenUserActive.setOnCheckedChangeListener { _, v -> prefs.pauseWhenUserActive = v }
        b.blockPaidTicket.setOnCheckedChangeListener { _, v -> prefs.blockPaidTicket = v }

        b.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val state = if (prefs.enabled) "執行中" else "已停止"
        b.statusText.text = "狀態：$state / ${prefs.mode}"
    }
}
