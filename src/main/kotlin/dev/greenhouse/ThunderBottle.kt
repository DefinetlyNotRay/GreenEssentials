package dev.greenhouse

internal object ThunderBottle {
    const val CHARGE_ID = "THUNDER_BOTTLE_CHARGE"
    const val COMPLETED_ID = "THUNDER_BOTTLES_FILLED"
    const val FILLED_ID = "THUNDER_IN_A_BOTTLE"
    const val EMPTY_ID = "THUNDER_IN_A_BOTTLE_EMPTY"
    const val CAPACITY = 50_000L

    private val message = Regex("^ZOOP! Your Empty Thunder in a Bottle has absorbed ([\\d,]+) charge, calming your Thunderling\\.$")

    fun charge(text: String): Long? = message.matchEntire(text)?.groupValues?.get(1)
        ?.replace(",", "")?.toLongOrNull()?.takeIf { it > 0 }

    fun auctionAverage(data: com.google.gson.JsonObject): Double? {
        val sales = data["totalSales"]?.asLong ?: return null
        return data["avgPrice"]?.asDouble?.takeIf { sales > 0 && it.isFinite() && it > 0 }
    }
}
