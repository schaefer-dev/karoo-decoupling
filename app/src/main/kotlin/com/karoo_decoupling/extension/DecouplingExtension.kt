package com.karoo_decoupling.extension

import com.karoo_decoupling.data.SettingsRepository
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension

class DecouplingExtension : KarooExtension(EXTENSION_ID, "0.1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var wBalEngine: WBalEngine

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            DecouplingDataType(karooSystem, EXTENSION_ID),
            DecouplingTrendDataType(karooSystem, EXTENSION_ID),
            WBalPercentDataType(wBalEngine, EXTENSION_ID),
            WBalRemainingDataType(wBalEngine, EXTENSION_ID),
            WBalStatusDataType(wBalEngine, EXTENSION_ID),
            WBalEtaDataType(wBalEngine, EXTENSION_ID),
            WBalRateDataType(wBalEngine, EXTENSION_ID),
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        wBalEngine = WBalEngine(karooSystem, settingsRepository)
        karooSystem.connect()
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "karoo_decoupling"
    }
}
