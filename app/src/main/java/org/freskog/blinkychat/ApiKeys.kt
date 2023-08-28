package org.freskog.blinkychat

import android.content.Context
import java.io.IOException
import java.util.Properties

object ApiKeys {
    fun retrieveAPIKey(name:String, context: Context):String {
        val assetManager = context.assets
        val properties = Properties()
        return try {
            assetManager.open("api-keys.properties").use { inputStream ->
                properties.load(inputStream)
            }
            properties.getProperty(name, "${name}-missing")
        } catch (e: IOException) {
            "${name}-missing"
        }
    }
}