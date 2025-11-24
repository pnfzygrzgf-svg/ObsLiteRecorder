package com.example.obsliterecorder.util

/**
 * COBS (Consistent Overhead Byte Stuffing) Hilfsfunktionen.
 *
 * Annahmen:
 * - Jede Nachricht wird mit COBS enkodiert.
 * - Auf der Leitung / in der Datei wird NACH jedem COBS-Block ein 0x00 als Trenner gesendet.
 * - Für decode() übergeben wir nur den Block (inkl. evtl. 0x00 am Ende), so wie er vom Stream kommt.
 */
object CobsUtils {

    /**
     * COBS-Encode eines ByteArrays.
     * Gibt die COBS-Bytes OHNE abschließendes 0x00 zurück.
     * (Das 0x00 wird separat als Frame-Delimiter geschrieben.)
     */
    fun encode2(data: ByteArray?): List<Byte> {
        if (data == null || data.isEmpty()) {
            return emptyList()
        }

        val out = ArrayList<Byte>(data.size + data.size / 254 + 2)
        var index = 0

        while (index < data.size) {
            val codeIndex = out.size
            out.add(0) // Platzhalter für Code
            var code = 1

            while (index < data.size && code < 0xFF) {
                val b = data[index++]
                if (b.toInt() == 0) {
                    break
                }
                out.add(b)
                code++
            }

            out[codeIndex] = code.toByte()
        }

        return out
    }

    /**
     * COBS-Decode für einen Block.
     * Der übergebene Block darf am Ende ein 0x00 enthalten – dieses wird ignoriert.
     */
    fun decode(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data

        val out = ArrayList<Byte>(data.size)
        var index = 0

        // Wenn das letzte Byte 0x00 ist (Frame-Delimiter), ignorieren
        val effectiveLength = if (data.last().toInt() == 0x00) data.size - 1 else data.size

        while (index < effectiveLength) {
            val code = data[index].toInt() and 0xFF
            if (code == 0) {
                // Ungültiger Frame – abbrechen
                break
            }
            index++

            val end = index + code - 1
            while (index < end && index < effectiveLength) {
                out.add(data[index])
                index++
            }

            // Wenn der Code < 0xFF und wir noch nicht am Ende sind, fügen wir ein implizites 0x00 ein
            if (code < 0xFF && index < effectiveLength) {
                out.add(0)
            }
        }

        return out.toByteArray()
    }

    /**
     * Komfortvariante: LinkedList/Collection<Byte> dekodieren.
     */
    fun decode(bytes: Collection<Byte>): ByteArray {
        val arr = ByteArray(bytes.size)
        var i = 0
        for (b in bytes) {
            arr[i++] = b
        }
        return decode(arr)
    }
}
