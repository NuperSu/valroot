package io.valroot.core.exchange.imports

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads a ZIP archive held in memory through its central directory, the authoritative list of entries with their
 * sizes and offsets. Unlike a streaming reader this handles entries whose sizes follow their data (data descriptors),
 * which exchanges write for stored and compressed members alike. Every entry is checked against its CRC-32.
 */
internal object ZipDirectory {
    class Entry(
        val name: String,
        val directory: Boolean,
        val encrypted: Boolean,
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val crc: Long,
        val localOffset: Long,
    )

    fun entries(
        label: String,
        bytes: ByteArray,
    ): List<Entry> {
        val end = endOfDirectory(bytes) ?: throw ImportFileException("\"$label\" is a damaged archive")
        val count = u16(bytes, end + 10)
        var offset = u32(bytes, end + 16).toInt()
        if (u32(bytes, end + 16) == 0xFFFFFFFFL ||
            count == 0xFFFF
        ) {
            throw ImportFileException("\"$label\" is a ZIP64 archive, which is not supported")
        }
        return List(count) {
            if (offset + 46 > bytes.size || u32(bytes, offset) != CENTRAL) throw ImportFileException("\"$label\" is a damaged archive")
            val flags = u16(bytes, offset + 8)
            val nameLength = u16(bytes, offset + 28)
            val extraLength = u16(bytes, offset + 30)
            val commentLength = u16(bytes, offset + 32)
            if (offset + 46 + nameLength > bytes.size) throw ImportFileException("\"$label\" is a damaged archive")
            val name =
                String(bytes, offset + 46, nameLength, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
            val entry =
                Entry(
                    name = name,
                    directory = name.endsWith("/"),
                    encrypted = flags and 1 != 0,
                    method = u16(bytes, offset + 10),
                    compressedSize = u32(bytes, offset + 20),
                    size = u32(bytes, offset + 24),
                    crc = u32(bytes, offset + 16),
                    localOffset = u32(bytes, offset + 42),
                )
            if (entry.compressedSize == 0xFFFFFFFFL || entry.size == 0xFFFFFFFFL || entry.localOffset == 0xFFFFFFFFL) {
                throw ImportFileException("\"$label\" is a ZIP64 archive, which is not supported")
            }
            offset += 46 + nameLength + extraLength + commentLength
            entry
        }
    }

    /** The entry's content; more than [limit] bytes fails with [tooLarge]. */
    fun content(
        label: String,
        bytes: ByteArray,
        entry: Entry,
        limit: Long,
        tooLarge: () -> String,
    ): ByteArray {
        val local = entry.localOffset.toInt()
        if (local + 30 > bytes.size || u32(bytes, local) != LOCAL) throw ImportFileException("\"$label\" is a damaged archive")
        // Either header may mark encryption; both are checked.
        if (entry.encrypted || u16(bytes, local + 6) and 1 != 0) {
            throw ImportFileException("\"$label\" is password-protected; export it again without a password")
        }
        if (entry.size > limit) throw ImportFileException(tooLarge())
        val start = local + 30 + u16(bytes, local + 26) + u16(bytes, local + 28)
        val stop = start + entry.compressedSize
        if (stop > bytes.size) throw ImportFileException("\"$label\" is a damaged archive")
        val content =
            when (entry.method) {
                STORED -> bytes.copyOfRange(start, stop.toInt())
                DEFLATED -> inflate(label, bytes, start, entry.compressedSize.toInt(), entry.size)
                else -> throw ImportFileException("\"${entry.name}\" in \"$label\" uses a compression method Valroot cannot read")
            }
        if (content.size.toLong() != entry.size) throw ImportFileException("\"$label\" is a damaged archive")
        val crc = CRC32().apply { update(content) }.value
        if (crc != entry.crc) throw ImportFileException("\"$label\" is a damaged archive")
        return content
    }

    private fun inflate(
        label: String,
        bytes: ByteArray,
        start: Int,
        length: Int,
        size: Long,
    ): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, start, length)
            val out = ByteArray(size.toInt())
            var written = 0
            while (written < out.size) {
                val count = inflater.inflate(out, written, out.size - written)
                if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                written += count
            }
            // More output than the directory states is a damaged (or hostile) entry.
            if (written != out.size || inflater.inflate(ByteArray(1)) != 0) throw ImportFileException("\"$label\" is a damaged archive")
            return out
        } catch (_: DataFormatException) {
            throw ImportFileException("\"$label\" is a damaged archive")
        } finally {
            inflater.end()
        }
    }

    private fun endOfDirectory(bytes: ByteArray): Int? {
        val lowest = maxOf(0, bytes.size - 22 - 0xFFFF)
        var at = bytes.size - 22
        while (at >= lowest) {
            if (u32(bytes, at) == END) return at
            at--
        }
        return null
    }

    private fun u16(
        bytes: ByteArray,
        at: Int,
    ) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun u32(
        bytes: ByteArray,
        at: Int,
    ): Long = (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)

    private const val END = 0x06054b50L
    private const val CENTRAL = 0x02014b50L
    private const val LOCAL = 0x04034b50L
    private const val STORED = 0
    private const val DEFLATED = 8
}
