package com.ishu1519.aerenas.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.security.SecureRandom

class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aerenas_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USERNAME      = "username"
        private const val KEY_PASSWORD      = "password"
        private const val KEY_PORT          = "port"
        private const val KEY_ROOT_PATH     = "root_path"
        private const val KEY_AUTO_START    = "auto_start_boot"
        private const val KEY_INITIALIZED   = "initialized"
        private const val DEFAULT_PORT      = 8080
        private val CHARSET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }

    init {
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            prefs.edit()
                .putString(KEY_USERNAME, "aere")
                .putString(KEY_PASSWORD, generatePassword())
                .putInt(KEY_PORT, DEFAULT_PORT)
                .putString(KEY_ROOT_PATH,
                    Environment.getExternalStorageDirectory().absolutePath + "/AereNAS")
                .putBoolean(KEY_AUTO_START, true)
                .putBoolean(KEY_INITIALIZED, true)
                .apply()
        }
    }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "aere") ?: "aere"
        set(v) = prefs.edit().putString(KEY_USERNAME, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    var rootPath: String
        get() = prefs.getString(KEY_ROOT_PATH,
            Environment.getExternalStorageDirectory().absolutePath + "/AereNAS") ?: ""
        set(v) = prefs.edit().putString(KEY_ROOT_PATH, v).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_START, v).apply()

    private fun generatePassword(length: Int = 12): String {
        val rng = SecureRandom()
        return (1..length).map { CHARSET[rng.nextInt(CHARSET.length)] }.joinToString("")
    }

    fun regeneratePassword(): String {
        val newPass = generatePassword()
        password = newPass
        return newPass
    }
}
