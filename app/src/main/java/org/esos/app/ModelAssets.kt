package org.esos.app

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object ModelAssets {
    fun unpack(context: Context, modelName: String, keepGoing: () -> Boolean): File {
        val root = File(context.filesDir, "models")
        val destination = File(root, modelName)
        val ready = File(destination, ".complete")
        if (ready.isFile) return destination
        destination.deleteRecursively()
        val rootPath = root.canonicalPath + File.separator
        context.assets.open("models/$modelName.zip").use { archive ->
            ZipInputStream(archive).use { zip ->
                while (keepGoing()) {
                    val entry = zip.nextEntry ?: break
                    require(entry.name.startsWith("$modelName/")) { "Ongeldig modelarchief" }
                    val output = File(root, entry.name)
                    require(output.canonicalPath.startsWith(rootPath)) { "Ongeldig modelpad" }
                    if (entry.isDirectory) output.mkdirs() else {
                        output.parentFile?.mkdirs()
                        output.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
        if (!keepGoing()) error("Laden geannuleerd")
        require(File(destination, "am/final.mdl").isFile && File(destination, "conf/model.conf").isFile) {
            "Model onvolledig"
        }
        ready.writeText("ok")
        return destination
    }
}