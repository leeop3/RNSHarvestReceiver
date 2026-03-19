package com.harvest.rns.ui.radio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.harvest.rns.data.model.RadioConfig
import com.harvest.rns.databinding.FragmentRadioConfigBinding
import com.harvest.rns.ui.main.MainViewModel

class RadioConfigFragment : Fragment() {

    private var _binding: FragmentRadioConfigBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRadioConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupPresetSpinner()
        setupBandwidthSpinner()
        setupCodingRateSpinner()

        // Observe current config and populate fields
        viewModel.radioConfig.observe(viewLifecycleOwner) { config ->
            populateFields(config)
        }

        // Apply button
        binding.applyBtn.setOnClickListener {
            applyConfig()
        }

        // Connection hint
        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            val connected = status is com.harvest.rns.data.model.ConnectionStatus.Connected
            binding.applyBtn.isEnabled = connected
            binding.connectionWarning.visibility = if (connected) View.GONE else View.VISIBLE
        }
    }

    // ─── Preset Spinner ───────────────────────────────────────────────────────

    private fun setupPresetSpinner() {
        val labels = listOf("— Select preset —") +
                RadioConfig.ALL_PRESETS.map { it.first }
        val adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.presetSpinner.adapter = adapter

        binding.presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) return
                val config = RadioConfig.ALL_PRESETS[pos - 1].second
                populateFields(config)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ─── Bandwidth Spinner ────────────────────────────────────────────────────

    private val bandwidths = listOf(
        7_800, 10_400, 15_600, 20_800, 31_250,
        41_700, 62_500, 125_000, 250_000, 500_000
    )
    private val bandwidthLabels = listOf(
        "7.8 kHz", "10.4 kHz", "15.6 kHz", "20.8 kHz", "31.25 kHz",
        "41.7 kHz", "62.5 kHz", "125 kHz", "250 kHz", "500 kHz"
    )

    private fun setupBandwidthSpinner() {
        val adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, bandwidthLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.bandwidthSpinner.adapter = adapter
    }

    // ─── Coding Rate Spinner ──────────────────────────────────────────────────

    private val codingRates     = listOf(5, 6, 7, 8)
    private val codingRateLabels = listOf("4/5", "4/6", "4/7", "4/8")

    private fun setupCodingRateSpinner() {
        val adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, codingRateLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.codingRateSpinner.adapter = adapter
    }

    // ─── Populate fields from config ──────────────────────────────────────────

    private fun populateFields(config: RadioConfig) {
        binding.frequencyInput.setText((config.frequencyHz / 1_000_000.0).toString())
        binding.sfSeekBar.progress = config.spreadingFactor - 7   // SF7=0 … SF12=5
        binding.sfValueText.text   = "SF${config.spreadingFactor}"
        binding.txPowerSeekBar.progress = config.txPower
        binding.txPowerValueText.text   = "${config.txPower} dBm"

        val bwIdx = bandwidths.indexOfFirst { it == config.bandwidthHz }.coerceAtLeast(0)
        binding.bandwidthSpinner.setSelection(bwIdx)

        val crIdx = codingRates.indexOfFirst { it == config.codingRate }.coerceAtLeast(0)
        binding.codingRateSpinner.setSelection(crIdx)

        updateSummaryLine(config)
    }

    // ─── Apply button handler ─────────────────────────────────────────────────

    private fun applyConfig() {
        try {
            val freqMHz = binding.frequencyInput.text.toString().trim().toDouble()
            val freqHz  = (freqMHz * 1_000_000).toLong()

            if (freqHz < 100_000_000L || freqHz > 1_000_000_000L) {
                Toast.makeText(requireContext(), "Frequency must be between 100–1000 MHz", Toast.LENGTH_SHORT).show()
                return
            }

            val sf     = binding.sfSeekBar.progress + 7
            val txPwr  = binding.txPowerSeekBar.progress
            val bw     = bandwidths[binding.bandwidthSpinner.selectedItemPosition]
            val cr     = codingRates[binding.codingRateSpinner.selectedItemPosition]

            val config = RadioConfig(freqHz, bw, sf, cr, txPwr)
            viewModel.applyRadioConfig(config)

            Toast.makeText(
                requireContext(),
                "Radio config sent to RNode:\n${config.frequencyMHz()} BW=${config.bandwidthKHz()} SF$sf CR=4/$cr ${txPwr}dBm",
                Toast.LENGTH_LONG
            ).show()

            updateSummaryLine(config)

        } catch (e: NumberFormatException) {
            Toast.makeText(requireContext(), "Invalid frequency value", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSummaryLine(config: RadioConfig) {
        binding.configSummaryText.text =
            "${config.frequencyMHz()}  BW ${config.bandwidthKHz()}  SF${config.spreadingFactor}  CR 4/${config.codingRate}  ${config.txPower} dBm"
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Reconnect seek bar change listeners after state restore
        binding.sfSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                binding.sfValueText.text = "SF${p + 7}"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        binding.txPowerSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                binding.txPowerValueText.text = "$p dBm"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
