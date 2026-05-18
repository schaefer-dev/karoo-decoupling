package com.karoo_decoupling.extension

data class DecouplingResult(
    val efFirst: Double,
    val efSecond: Double,
    val driftPct: Double,
)

class DecouplingCalculator {
    private data class Sample(val movingSec: Int, val power: Double, val hr: Double)

    private val samples = ArrayList<Sample>()
    private var lastMovingSec = -1

    fun reset() {
        samples.clear()
        lastMovingSec = -1
    }

    /** Append a sample, dropping duplicate-second emissions and obviously bad values. */
    fun addSample(movingSec: Int, power: Double, hr: Double) {
        if (movingSec <= lastMovingSec) return
        if (hr <= 0.0 || power < 0.0) return
        samples.add(Sample(movingSec, power, hr))
        lastMovingSec = movingSec
    }

    /**
     * Returns null until there is enough data to be meaningful (>=120s of moving time
     * with samples spanning both halves).
     */
    fun result(): DecouplingResult? {
        if (samples.size < 2) return null
        val totalSec = samples.last().movingSec
        if (totalSec < 120) return null
        val mid = totalSec / 2

        var p1 = 0.0; var h1 = 0.0; var n1 = 0
        var p2 = 0.0; var h2 = 0.0; var n2 = 0
        for (s in samples) {
            if (s.movingSec <= mid) {
                p1 += s.power; h1 += s.hr; n1++
            } else {
                p2 += s.power; h2 += s.hr; n2++
            }
        }
        if (n1 == 0 || n2 == 0) return null
        val avgP1 = p1 / n1; val avgH1 = h1 / n1
        val avgP2 = p2 / n2; val avgH2 = h2 / n2
        if (avgH1 <= 0.0 || avgH2 <= 0.0) return null
        val ef1 = avgP1 / avgH1
        val ef2 = avgP2 / avgH2
        if (ef1 == 0.0) return null
        val drift = (ef1 - ef2) / ef1 * 100.0
        return DecouplingResult(ef1, ef2, drift)
    }
}
