package dev.greenhouse

/** Combines inventory and sack signals in base crop units; conversion itself is not a harvest. */
internal class HarvestLedger(private val admit: (String) -> Boolean = { false }) {
    private var inventory: Map<String, Long>? = null
    private val gains = mutableMapOf<String, Long>()
    private val sacks = mutableMapOf<String, Long>()
    private val exclusions = mutableMapOf<String, Long>()
    private data class Credit(val id: String, var count: Long, val sack: Boolean, val expires: Long)
    private val credits = mutableListOf<Credit>()
    private data class Returned(val id: String,var inventory: Long,var sacks: Long,val expires: Long)
    private val returned=mutableListOf<Returned>()
    fun ignoreReturn(id: String,count: Long=1,now: Long=System.currentTimeMillis()) {
        if (count>0) returned.add(Returned(id,count,count,now+65_000))
    }
    fun finishSackReport() { returned.clear() }
    private fun removeReturns(id: String,count: Long,sack: Boolean): Long {
        var remaining=count
        returned.filter { it.id==id }.forEach {
            val available=if(sack) it.sacks else it.inventory
            val used=minOf(remaining,available)
            if(sack) it.sacks-=used else it.inventory-=used
            remaining-=used
        }
        return remaining
    }
    fun snapshot(items: Map<String, Long>, eligible: Boolean) {
        val current = Catalog.canonical(items)
        val old = inventory
        inventory = current
        if (!eligible || old == null) return
        current.forEach { (id, count) ->
            val delta = removeReturns(id,(count - old.getOrDefault(id, 0)).coerceAtLeast(0),false)
            if (delta > 0 && admit(id)) gains[id] = gains.getOrDefault(id, 0) + delta
        }
    }
    fun sack(id: String, count: Long, admission: (String) -> Boolean = admit) {
        val unit = Catalog.units[id] ?: return
        val gain=removeReturns(unit.base,(count*unit.amount).coerceAtLeast(0),true)
        if (gain > 0 && admission(unit.base)) sacks[unit.base] = sacks.getOrDefault(unit.base, 0) + gain
    }
    fun mutation(id: String, fromShard: Boolean) {
        require(id == "TIMESTALK" || id == "ZOMBUD")
        val destination = if (fromShard) sacks else gains
        destination[id] = destination.getOrDefault(id, 0) + 1
    }
    fun exclude(id: String, count: Long) {
        val unit = Catalog.units[id] ?: return
        exclusions[unit.base] = exclusions.getOrDefault(unit.base, 0) + count * unit.amount
    }
    fun flush(now: Long = System.currentTimeMillis()): Map<String, Long> {
        returned.removeIf { it.expires < now || it.inventory==0L && it.sacks==0L }
        credits.removeIf { it.expires < now }
        fun consume(id: String, amount: Long, fromSack: Boolean): Long {
            var remaining = amount
            credits.filter { it.id == id && it.sack != fromSack }.forEach {
                val used = minOf(remaining, it.count); it.count -= used; remaining -= used
            }
            return remaining
        }
        val result = (gains.keys + sacks.keys).associateWith { id ->
            val inventoryGain = consume(id, gains[id] ?: 0, false)
            val sackGain = consume(id, sacks[id] ?: 0, true)
            if (inventoryGain > sackGain) credits.add(Credit(id, inventoryGain-sackGain, false, now+65_000))
            if (sackGain > inventoryGain) credits.add(Credit(id, sackGain-inventoryGain, true, now+2500))
            (maxOf(inventoryGain, sackGain) - (exclusions[id] ?: 0)).coerceAtLeast(0)
        }.filterValues { it > 0 }
        credits.removeIf { it.count == 0L }
        gains.clear(); sacks.clear(); exclusions.clear()
        return result
    }
    fun reset() { inventory = null; gains.clear(); sacks.clear(); exclusions.clear(); credits.clear(); returned.clear() }
}
