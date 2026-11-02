package com.telmex.controlparental

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.telmex.controlparental.databinding.ActivityMainBinding
import com.telmex.controlparental.databinding.DialogSettingsBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager
    private lateinit var routerService: HuaweiRouterService
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)
        routerService = HuaweiRouterService(this, binding.hiddenWebView, settings)

        setupUI()
        setupListeners()
        updateDashboard(settings.isFilterActive)
        checkActiveTimer()
    }

    private fun setupUI() {
        if (settings.routerPass.isEmpty()) {
            showSettingsDialog(isFirstTime = true)
        }
    }

    private fun setupListeners() {
        binding.btnMainToggle.setOnClickListener {
            val newState = !settings.isFilterActive
            executeFilterToggle(newState)
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog(isFirstTime = false)
        }

        binding.btnTimer30.setOnClickListener { startPauseTimer(30 * 60 * 1000L) }
        binding.btnTimer60.setOnClickListener { startPauseTimer(60 * 60 * 1000L) }
        binding.btnTimer120.setOnClickListener { startPauseTimer(120 * 60 * 1000L) }
    }

    private fun executeFilterToggle(enable: Boolean, onDone: (() -> Unit)? = null) {
        setLoadingState(true)

        routerService.toggleFilter(enable, object : HuaweiRouterService.RouterCallback {
            override fun onStatus(message: String) {
                runOnUiThread {
                    binding.tvActionLog.text = message
                }
            }

            override fun onSuccess(filterActive: Boolean) {
                runOnUiThread {
                    setLoadingState(false)
                    updateDashboard(filterActive)
                    vibrate()

                    val msg = if (filterActive) {
                        "¡Internet pausado para los dispositivos seleccionados!"
                    } else {
                        "¡Internet reanudado con éxito!"
                    }
                    binding.tvActionLog.text = msg
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    onDone?.invoke()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    setLoadingState(false)
                    binding.tvActionLog.text = "Error: $error"
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun startPauseTimer(durationMillis: Long) {
        // Pausar primero el internet si no está pausado
        if (!settings.isFilterActive) {
            executeFilterToggle(true) {
                launchTimer(durationMillis)
            }
        } else {
            launchTimer(durationMillis)
        }
    }

    private fun launchTimer(durationMillis: Long) {
        countDownTimer?.cancel()
        val endTime = System.currentTimeMillis() + durationMillis
        settings.timerEndTime = endTime

        binding.tvActiveTimer.visibility = View.VISIBLE

        countDownTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                binding.tvActiveTimer.text = String.format("Pausa activa: %02d:%02d restantes", minutes, seconds)
            }

            override fun onFinish() {
                binding.tvActiveTimer.visibility = View.GONE
                settings.timerEndTime = 0L
                Toast.makeText(this@MainActivity, "Tiempo agotado: reactivando internet...", Toast.LENGTH_SHORT).show()
                executeFilterToggle(false)
            }
        }.start()
    }

    private fun checkActiveTimer() {
        val remaining = settings.timerEndTime - System.currentTimeMillis()
        if (remaining > 0 && settings.isFilterActive) {
            launchTimer(remaining)
        } else {
            settings.timerEndTime = 0L
            binding.tvActiveTimer.visibility = View.GONE
        }
    }

    private fun updateDashboard(isPaused: Boolean) {
        if (isPaused) {
            // Estado: INTERNET PAUSADO (Bloqueo activo)
            binding.layoutStatusContainer.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red_bg))
            binding.ivStatusIcon.setImageResource(android.R.drawable.presence_busy)
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_red))

            binding.tvStatusTitle.text = getString(R.string.status_paused)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            binding.tvStatusDesc.text = getString(R.string.status_paused_desc)

            binding.btnMainToggle.text = getString(R.string.btn_resume)
            binding.btnMainToggle.setIconResource(android.R.drawable.ic_media_play)
            binding.btnMainToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green)
        } else {
            // Estado: INTERNET ACTIVO (Sin bloqueo)
            binding.layoutStatusContainer.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
            binding.ivStatusIcon.setImageResource(android.R.drawable.presence_online)
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_green))

            binding.tvStatusTitle.text = getString(R.string.status_connected)
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.tvStatusDesc.text = getString(R.string.status_connected_desc)

            binding.btnMainToggle.text = getString(R.string.btn_pause)
            binding.btnMainToggle.setIconResource(android.R.drawable.ic_media_pause)
            binding.btnMainToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)

            binding.tvActiveTimer.visibility = View.GONE
            countDownTimer?.cancel()
        }
    }

    private fun setLoadingState(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnMainToggle.isEnabled = !loading
        binding.btnTimer30.isEnabled = !loading
        binding.btnTimer60.isEnabled = !loading
        binding.btnTimer120.isEnabled = !loading
    }

    private fun showSettingsDialog(isFirstTime: Boolean) {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))

        dialogBinding.etModemIp.setText(settings.routerIp)
        dialogBinding.etModemUser.setText(settings.routerUser)
        dialogBinding.etModemPass.setText(settings.routerPass)

        val builder = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(!isFirstTime)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val ip = dialogBinding.etModemIp.text.toString().trim()
                val user = dialogBinding.etModemUser.text.toString().trim()
                val pass = dialogBinding.etModemPass.text.toString().trim()

                if (ip.isNotEmpty()) settings.routerIp = ip
                if (user.isNotEmpty()) settings.routerUser = user
                settings.routerPass = pass

                Toast.makeText(this, "Ajustes guardados correctamente", Toast.LENGTH_SHORT).show()
            }

        if (!isFirstTime) {
            builder.setNegativeButton(R.string.btn_cancel, null)
        }

        builder.show()
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(150)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
