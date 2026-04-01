package com.example.mytts.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * JNI bridge to eSpeak-ng for text-to-phoneme conversion.
 * eSpeak-ng data files are extracted from assets to internal storage on first use.
 */
class EspeakBridge(private val context: Context) {

    companion object {
        private const val TAG = "EspeakBridge"
        private const val DATA_DIR = "espeak-ng-data"

        init {
            System.loadLibrary("espeak_jni")
        }
    }

    private var initialized = false

    /**
     * Initialize eSpeak-ng. Extracts data files from assets if needed.
     * Must be called before textToPhonemes().
     */
    fun initialize(): Boolean {
        if (initialized) return true

        try {
            val dataDir = extractDataIfNeeded()
            // eSpeak expects the parent of espeak-ng-data/, not the dir itself
            val parentPath = dataDir.parentFile?.absolutePath ?: return false

            val result = nativeInit(parentPath)
            if (result != 0) {
                Log.e(TAG, "nativeInit failed with code $result")
                return false
            }

            initialized = true
            Log.i(TAG, "eSpeak-ng initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize eSpeak-ng", e)
            return false
        }
    }

    /**
     * Convert text to IPA phonemes using eSpeak-ng.
     * Returns IPA string with '^' tie characters between multi-letter phonemes
     * (e.g., "d^ʒ" for the J sound, "t^ʃ" for CH).
     */
    fun textToPhonemes(text: String): String {
        if (!initialized) {
            Log.w(TAG, "eSpeak not initialized, returning empty")
            return ""
        }
        return nativeTextToPhonemes(text)
    }

    /**
     * Release eSpeak-ng resources.
     */
    fun terminate() {
        if (initialized) {
            nativeTerminate()
            initialized = false
        }
    }

    /**
     * Extract espeak-ng-data from assets to internal storage.
     * Uses a version marker file to avoid re-extracting on every launch.
     */
    private fun extractDataIfNeeded(): File {
        val destDir = File(context.filesDir, DATA_DIR)
        val versionFile = File(destDir, ".version")
        val currentVersion = "f6fed6c-en-only"

        if (versionFile.exists() && versionFile.readText() == currentVersion) {
            Log.d(TAG, "eSpeak data already extracted")
            return destDir
        }

        Log.i(TAG, "Extracting eSpeak-ng data to ${destDir.absolutePath}")

        // Clean and recreate
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        // Recursively copy from assets
        copyAssetDir(DATA_DIR, destDir)

        // Write version marker
        versionFile.writeText(currentVersion)
        Log.i(TAG, "eSpeak-ng data extracted successfully")

        return destDir
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val assets = context.assets.list(assetPath) ?: return

        if (assets.isEmpty()) {
            // It's a file, copy it
            context.assets.open(assetPath).use { input ->
                File(destDir.parentFile, destDir.name).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory, recurse
            destDir.mkdirs()
            for (child in assets) {
                copyAssetDir("$assetPath/$child", File(destDir, child))
            }
        }
    }

    // Native methods
    private external fun nativeInit(dataPath: String): Int
    private external fun nativeTextToPhonemes(text: String): String
    private external fun nativeTerminate()
}
