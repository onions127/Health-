package com.jcwear.coach.ble

import java.util.UUID

/**
 * Complete J2208A (J2208A2 17F6) BLE Protocol Encoder & Decoder.
 *
 * Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb
 * TX (Write):   0000fff6-0000-1000-8000-00805f9b34fb
 * RX (Notify):  0000fff7-0000-1000-8000-00805f9b34fb
 */
object J2208Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_UUID: UUID = UUID.fromString("0000fff6-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_UUID: UUID = UUID.fromString("0000fff7-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val OP_SET_TIME = 0x01
    const val OP_GET_BATTERY = 0x13
    const val OP_GET_MAC = 0x22
    const val OP_GET_VERSION = 0x27
    const val OP_MEASUREMENT = 0x28
    const val OP_SET_AUTO = 0x2A
    const val OP_GET_AUTO = 0x2B
    const val OP_GET_TIME = 0x41

    const val OP_HISTORY_TOTALS = 0x51
    const val OP_HISTORY_STEPS = 0x52
    const val OP_HISTORY_SLEEP = 0x53
    const val OP_HISTORY_HR = 0x55
    const val OP_HISTORY_HRV = 0x56
    const val OP_HISTORY_TEMP = 0x65
    const val OP_HISTORY_SPO2 = 0x66
    const val OP_REALTIME_PPI = 0x9B

    const val METRIC_HR = 1
    const val METRIC_SPO2 = 2
    const val METRIC_TEMP = 3
    const val METRIC_HRV = 4

    const val STAGE_DEEP = 1
    const val STAGE_LIGHT = 2
    const val STAGE_REM = 3
    const val STAGE_AWAKE = 5

    data class SleepBlockRecord(
        val deviceTime: String,
        val unitMinutes: Int,
        val count: Int,
        val deepMin: Int,
        val lightMin: Int,
        val remMin: Int,
        val awakeMin: Int,
        val stages: List<Int>,
        val rawHex: String
    )

    data class HrvRecord(
        val deviceTime: String,
        val hrvMs: Int,
        val vascularAging: Int,
        val hrBpm: Int,
        val stress: Int,
        val bpSys: Int,
        val bpDia: Int,
        val rawHex: String
    )

    data class HrRecord(
        val deviceTime: String,
        val bpm: Int,
        val rawHex: String
    )

    data class Spo2Record(
        val deviceTime: String,
        val spo2Pct: Int,
        val rawHex: String
    )

    data class TempRecord(
        val deviceTime: String,
        val tempC: Double,
        val rawHex: String
    )

    data class StepEpochRecord(
        val deviceTime: String,
        val steps: Int,
        val caloriesKcal: Double,
        val distanceKm: Double,
        val subMinuteSteps: List<Int>,
        val rawHex: String
    )

    data class LiveSensorFrame(
        val measureType: Int,
        val hrBpm: Int,
        val spo2Pct: Int,
        val hrvMs: Int,
        val stress: Int,
        val bpSys: Int,
        val bpDia: Int,
        val tempC: Double
    )

    fun toBcd(value: Int): Int {
        val clamped = value.coerceIn(0, 99)
        return ((clamped / 10) shl 4) or (clamped % 10)
    }

    fun fromBcd(byteVal: Int): Int {
        val u = byteVal and 0xFF
        return ((u ushr 4) and 0x0F) * 10 + (u and 0x0F)
    }

    fun u16le(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun u32le(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    fun buildFrame(opcode: Int, vararg args: Int): ByteArray {
        val pkt = ByteArray(16)
        pkt[0] = (opcode and 0xFF).toByte()
        for (i in args.indices) {
            if (i + 1 < 15) {
                pkt[i + 1] = (args[i] and 0xFF).toByte()
            }
        }
        var sum = 0
        for (i in 0 until 15) {
            sum += (pkt[i].toInt() and 0xFF)
        }
        pkt[15] = (sum and 0xFF).toByte()
        return pkt
    }

    /**
     * Builds the 0x2A SetAutomatic command for 24/7 (00:00 - 23:59, all 7 days) sampling.
     */
    fun buildSetAutoIntervalFrame(metricCode: Int, intervalMinutes: Int, enabled: Boolean = true): ByteArray {
        return buildFrame(
            OP_SET_AUTO,
            if (enabled) 2 else 0,
            0x00, 0x00, // 00:00 BCD
            0x23, 0x59, // 23:59 BCD
            0xFF,       // All 7 days bitmask
            intervalMinutes and 0xFF,
            (intervalMinutes ushr 8) and 0xFF,
            metricCode
        )
    }

    fun buildStartLiveMeasurementFrame(type: Int = 2, start: Boolean = true, openPpg: Boolean = false, openPpi: Boolean = true): ByteArray {
        return buildFrame(
            OP_MEASUREMENT,
            type,
            if (start) 1 else 0,
            if (openPpg) 1 else 0,
            if (openPpi) 1 else 0
        )
    }

    fun buildHistoryRequestFrame(opcode: Int, continuePage: Boolean = false): ByteArray {
        return buildFrame(opcode, if (continuePage) 2 else 0)
    }

    fun parseRecordDateTime(rec: ByteArray, offset: Int = 3): String? {
        if (rec.size < offset + 6) return null
        val yy = fromBcd(rec[offset].toInt())
        val mm = fromBcd(rec[offset + 1].toInt())
        val dd = fromBcd(rec[offset + 2].toInt())
        val hh = fromBcd(rec[offset + 3].toInt())
        val mi = fromBcd(rec[offset + 4].toInt())
        val ss = fromBcd(rec[offset + 5].toInt())
        if (mm !in 1..12 || dd !in 1..31 || hh !in 0..23 || mi !in 0..59 || ss !in 0..59) {
            return null
        }
        return String.format("20%02d-%02d-%02dT%02d:%02d:%02d", yy, mm, dd, hh, mi, ss)
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Parses both 130-byte (1-minute epoch) and stride-34 (5-minute epoch) 0x53 sleep packets.
     */
    fun parseSleepPacket(raw: ByteArray): List<SleepBlockRecord> {
        val clean = stripTerminator(raw, OP_HISTORY_SLEEP)
        if (clean.isEmpty()) return emptyList()

        if (clean.size == 130 && clean.size >= 10) {
            val dt = parseRecordDateTime(clean, 3) ?: return emptyList()
            val count = (clean[9].toInt() and 0xFF).coerceAtMost(clean.size - 10)
            val stages = (0 until count).map {
                val code = clean[10 + it].toInt() and 0xFF
                if (code == 30) STAGE_LIGHT else code
            }
            return listOf(
                SleepBlockRecord(
                    deviceTime = dt,
                    unitMinutes = 1,
                    count = count,
                    deepMin = stages.count { it == STAGE_DEEP },
                    lightMin = stages.count { it == STAGE_LIGHT },
                    remMin = stages.count { it == STAGE_REM },
                    awakeMin = stages.count { it == STAGE_AWAKE },
                    stages = stages,
                    rawHex = clean.toHex()
                )
            )
        }

        val stride = 34
        val out = mutableListOf<SleepBlockRecord>()
        val n = clean.size / stride
        for (i in 0 until n) {
            val rec = clean.copyOfRange(i * stride, (i + 1) * stride)
            val dt = parseRecordDateTime(rec, 3) ?: continue
            val count = (rec[9].toInt() and 0xFF).coerceAtMost(24)
            val stages = (0 until count).map { rec[10 + it].toInt() and 0xFF }
            out.add(
                SleepBlockRecord(
                    deviceTime = dt,
                    unitMinutes = 5,
                    count = count,
                    deepMin = stages.count { it == STAGE_DEEP } * 5,
                    lightMin = stages.count { it == STAGE_LIGHT } * 5,
                    remMin = stages.count { it == STAGE_REM } * 5,
                    awakeMin = stages.count { it == STAGE_AWAKE } * 5,
                    stages = stages,
                    rawHex = rec.toHex()
                )
            )
        }
        return out
    }

    fun parseHrvPacket(raw: ByteArray): List<HrvRecord> {
        val clean = stripTerminator(raw, OP_HISTORY_HRV)
        val stride = 15
        val out = mutableListOf<HrvRecord>()
        for (i in 0 until (clean.size / stride)) {
            val rec = clean.copyOfRange(i * stride, (i + 1) * stride)
            val dt = parseRecordDateTime(rec, 3) ?: continue
            out.add(
                HrvRecord(
                    deviceTime = dt,
                    hrvMs = rec[9].toInt() and 0xFF,
                    vascularAging = rec[10].toInt() and 0xFF,
                    hrBpm = rec[11].toInt() and 0xFF,
                    stress = rec[12].toInt() and 0xFF,
                    bpSys = rec[13].toInt() and 0xFF,
                    bpDia = rec[14].toInt() and 0xFF,
                    rawHex = rec.toHex()
                )
            )
        }
        return out
    }

    fun parseHrPacket(raw: ByteArray): List<HrRecord> {
        val clean = stripTerminator(raw, OP_HISTORY_HR)
        val stride = 10
        val out = mutableListOf<HrRecord>()
        for (i in 0 until (clean.size / stride)) {
            val rec = clean.copyOfRange(i * stride, (i + 1) * stride)
            val dt = parseRecordDateTime(rec, 3) ?: continue
            out.add(
                HrRecord(
                    deviceTime = dt,
                    bpm = rec[9].toInt() and 0xFF,
                    rawHex = rec.toHex()
                )
            )
        }
        return out
    }

    fun parseSpo2Packet(raw: ByteArray): List<Spo2Record> {
        val clean = stripTerminator(raw, OP_HISTORY_SPO2)
        val stride = 10
        val out = mutableListOf<Spo2Record>()
        for (i in 0 until (clean.size / stride)) {
            val rec = clean.copyOfRange(i * stride, (i + 1) * stride)
            val dt = parseRecordDateTime(rec, 3) ?: continue
            out.add(
                Spo2Record(
                    deviceTime = dt,
                    spo2Pct = rec[9].toInt() and 0xFF,
                    rawHex = rec.toHex()
                )
            )
        }
        return out
    }

    fun parseTempPacket(raw: ByteArray): List<TempRecord> {
        val clean = stripTerminator(raw, OP_HISTORY_TEMP)
        val stride = 11
        val out = mutableListOf<TempRecord>()
        for (i in 0 until (clean.size / stride)) {
            val rec = clean.copyOfRange(i * stride, (i + 1) * stride)
            val dt = parseRecordDateTime(rec, 3) ?: continue
            val tempC = u16le(rec, 9) * 0.1
            out.add(
                TempRecord(
                    deviceTime = dt,
                    tempC = Math.round(tempC * 10.0) / 10.0,
                    rawHex = rec.toHex()
                )
            )
        }
        return out
    }

    fun parseStepsPacket(raw: ByteArray): List<StepEpochRecord> {
        val clean = stripTerminator(raw, OP_HISTORY_STEPS)
        val stride = 25
        val out = mutableListOf<StepEpochRecord>()
        for (i in 0 until (clean.size / stride)) {
            val rec = clean.copyOfRange(i * stride, (i + 1) * stride)
            val dt = parseRecordDateTime(rec, 3) ?: continue
            val steps = u16le(rec, 9)
            val kcal = u16le(rec, 11) / 100.0
            val km = u16le(rec, 13) / 100.0
            val subSteps = (15 until 25).map { rec[it].toInt() and 0xFF }
            out.add(
                StepEpochRecord(
                    deviceTime = dt,
                    steps = steps,
                    caloriesKcal = kcal,
                    distanceKm = km,
                    subMinuteSteps = subSteps,
                    rawHex = rec.toHex()
                )
            )
        }
        return out
    }

    fun parseLiveSensorFrame(raw: ByteArray): LiveSensorFrame? {
        if (raw.size < 10 || (raw[0].toInt() and 0xFF) != OP_MEASUREMENT) return null
        return LiveSensorFrame(
            measureType = raw[1].toInt() and 0xFF,
            hrBpm = raw[2].toInt() and 0xFF,
            spo2Pct = raw[3].toInt() and 0xFF,
            hrvMs = raw[4].toInt() and 0xFF,
            stress = raw[5].toInt() and 0xFF,
            bpSys = raw[6].toInt() and 0xFF,
            bpDia = raw[7].toInt() and 0xFF,
            tempC = Math.round(u16le(raw, 8) * 1.0) / 10.0
        )
    }

    fun isEndOfHistory(raw: ByteArray, opcode: Int): Boolean {
        if (raw.size < 2) return false
        val bLast2 = raw[raw.size - 2].toInt() and 0xFF
        val bLast1 = raw[raw.size - 1].toInt() and 0xFF
        return bLast2 == (opcode and 0xFF) && bLast1 == 0xFF
    }

    private fun stripTerminator(raw: ByteArray, opcode: Int): ByteArray {
        if (isEndOfHistory(raw, opcode)) {
            if (raw.size == 2) return ByteArray(0)
            return raw.copyOfRange(0, raw.size - 2)
        }
        return raw
    }
}
