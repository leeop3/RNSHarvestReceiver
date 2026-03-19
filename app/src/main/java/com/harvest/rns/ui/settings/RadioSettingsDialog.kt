package com.harvest.rns.ui.settings

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.harvest.rns.R
import com.harvest.rns.data.model.RadioConfig
import com.harvest.rns.ui.main.MainViewModel

class RadioSettingsDialog : DialogFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_radio_settings, null)

        val bwSpinner  = view.findViewById<Spinner>(R.id.bwSpinner)
        val sfEdit     = view.findViewById<EditText>(R.id.sfEdit)
        val crEdit     = view.findViewById<EditText>(R.id.crEdit)
        val txEdit     = view.findViewById<EditText>(R.id.txEdit)
        val freqText   = view.findViewById<TextView>(R.id.freqText)
        val statusText = view.findViewById<TextView>(R.id.radioStatusText)

        freqText.text = "Frequency: 433.025 MHz (fixed)"

        // Bandwidth spinner
        val bwLabels = RadioConfig.BANDWIDTH_OPTIONS.map { it.second }
        bwSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, bwLabels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Load saved settings
        val prefs  = ctx.getSharedPreferences(RadioConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedBw = prefs.getInt(RadioConfig.PREF_BW, RadioConfig.DEFAULT.bandwidthHz)
        val savedSf = prefs.getInt(RadioConfig.PREF_SF, RadioConfig.DEFAULT.spreadingFactor)
        val savedCr = prefs.getInt(RadioConfig.PREF_CR, RadioConfig.DEFAULT.codingRate)
        val savedTx = prefs.getInt(RadioConfig.PREF_TXPOWER, RadioConfig.DEFAULT.txPower)

        val bwIdx = RadioConfig.BANDWIDTH_OPTIONS.indexOfFirst { it.first == savedBw }.coerceAtLeast(0)
        bwSpinner.setSelection(bwIdx)
        sfEdit.setText(savedSf.toString())
        crEdit.setText(savedCr.toString())
        txEdit.setText(savedTx.toString())

        // Show current connection state
        viewModel.connectionStatus.observe(this) { status ->
            statusText.text = when (status) {
                is com.harvest.rns.data.model.ConnectionStatus.Connected ->
                    "✓ Connected — settings will apply immediately"
                else ->
                    "ⓘ Settings will be saved and applied when RNode connects"
            }
        }

        return AlertDialog.Builder(ctx, R.style.RadioDialogTheme)
            .setTitle("Radio Settings")
            .setView(view)
            .setPositiveButton("Save & Apply") { _, _ ->
                val bw = RadioConfig.BANDWIDTH_OPTIONS[bwSpinner.selectedItemPosition].first
                val sf = sfEdit.text.toString().toIntOrNull()?.coerceIn(7, 12) ?: savedSf
                val cr = crEdit.text.toString().toIntOrNull()?.coerceIn(5, 8) ?: savedCr
                val tx = txEdit.text.toString().toIntOrNull()?.coerceIn(0, 17) ?: savedTx

                // Save to prefs
                prefs.edit()
                    .putInt(RadioConfig.PREF_BW, bw)
                    .putInt(RadioConfig.PREF_SF, sf)
                    .putInt(RadioConfig.PREF_CR, cr)
                    .putInt(RadioConfig.PREF_TXPOWER, tx)
                    .apply()

                val config = RadioConfig(433_025_000L, bw, sf, cr, tx)
                viewModel.applyRadioConfig(config)
                Toast.makeText(ctx, "Radio settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    companion object {
        const val TAG = "RadioSettingsDialog"
    }
}
