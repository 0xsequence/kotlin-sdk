package technology.polygon.omswallet.storage

import android.util.AtomicFile
import java.io.File

/** Replaces [file]'s contents with [value] (UTF-8) through [AtomicFile]. */
@JvmSynthetic
internal fun writeTextAtomically(
    file: File,
    value: String,
) {
    val atomicFile = AtomicFile(file)
    val output = atomicFile.startWrite()
    try {
        output.write(value.toByteArray(Charsets.UTF_8))
        atomicFile.finishWrite(output)
    } catch (throwable: Throwable) {
        atomicFile.failWrite(output)
        throw throwable
    }
}
