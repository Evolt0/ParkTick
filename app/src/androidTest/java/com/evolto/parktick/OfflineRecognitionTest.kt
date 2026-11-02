package com.evolto.parktick

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.evolto.parktick.recognition.NativeBridge
import com.evolto.parktick.recognition.PlateRecognizer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineRecognitionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture(name: String): File {
        val file = File(context.cacheDir, "model-smoke-${UUID.randomUUID()}-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open("plates/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    @Test fun bluePlateRemainsCorrectAcrossRepeatedUseAndEngineRecreation() = runBlocking {
        val file = fixture("blue.png")
        try {
            repeat(2) {
                val recognizer = PlateRecognizer(context)
                try {
                    repeat(15) {
                        val candidates = recognizer.recognize(file)
                        assertTrue("Expected 藏DT5022, got ${candidates.map { it.code }}", candidates.any { it.code == "藏DT5022" })
                    }
                } finally {
                    recognizer.close()
                    recognizer.close()
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test fun newEnergyPlateUsesBundledProductionModels() = runBlocking {
        val file = fixture("green.jpg")
        val recognizer = PlateRecognizer(context)
        try {
            val candidates = recognizer.recognize(file)
            assertTrue("Expected 苏ED51712, got ${candidates.map { it.code }}", candidates.any { it.code == "苏ED51712" && it.type == 3 })
        } finally {
            recognizer.close()
            file.delete()
        }
    }

    @Test fun incompleteModelsFailInitializationInsteadOfPublishingInvalidHandle() {
        val directory = File(context.cacheDir, "missing-model-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            assertThrows(IllegalStateException::class.java) { NativeBridge.create(directory.absolutePath) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
