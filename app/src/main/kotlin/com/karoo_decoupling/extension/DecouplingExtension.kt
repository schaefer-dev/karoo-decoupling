package com.karoo_decoupling.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension

class DecouplingExtension : KarooExtension(EXTENSION_ID, "0.1.0") {

    private lateinit var karooSystem: KarooSystemService

    override val types: List<DataTypeImpl> by lazy {
        listOf(DecouplingDataType(karooSystem, EXTENSION_ID))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
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
