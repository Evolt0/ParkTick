package com.evolto.parktick.media

import android.content.Context
import com.evolto.parktick.domain.PhotoFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PhotoStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "parking_media")
    private val draftLock = Any()
    private val liveDrafts = mutableSetOf<String>()
    internal val operations = Mutex()
    private val ownedName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")

    fun newFile(): PhotoFile = synchronized(draftLock) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建照片目录")
        val id = UUID.randomUUID().toString()
        val name = "$id.jpg"
        val destination = file(name)
        if (!destination.createNewFile()) throw IOException("无法创建车辆照片")
        liveDrafts.add(name)
        PhotoFile(id, name, destination)
    }

    fun file(photoName: String): File {
        require(ownedName.matches(photoName)) { "无效的照片标识" }
        return File(directory, photoName)
    }

    suspend fun discard(photoName: String) = withContext(Dispatchers.IO) {
        operations.withLock {
            synchronized(draftLock) {
                // A confirmed photograph is adopted before the repository releases operations.
                // A late UI cancellation must never delete an active record's photograph.
                if (liveDrafts.remove(photoName)) deleteOwned(photoName)
            }
        }
    }

    internal fun adopt(photoName: String) = synchronized(draftLock) {
        liveDrafts.remove(photoName)
        Unit
    }

    internal fun isDraft(photoName: String): Boolean = synchronized(draftLock) {
        photoName in liveDrafts
    }

    internal fun deleteOwned(photoName: String) {
        val photo = file(photoName)
        if (photo.exists() && !photo.delete()) throw IOException("车辆照片清理未完成")
    }

    internal fun pruneUnreferenced(referenced: Set<String>) = synchronized(draftLock) {
        var failure: IOException? = null
        val files = if (!directory.exists()) emptyArray() else directory.listFiles()
            ?: throw IOException("无法读取车辆照片目录")
        for (photo in files) {
            if (ownedName.matches(photo.name) && photo.name !in referenced && photo.name !in liveDrafts) {
                try {
                    deleteOwned(photo.name)
                } catch (error: IOException) {
                    failure = error
                }
            }
        }
        failure?.let { throw it }
    }
}
