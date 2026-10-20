package dev.greenhouse

import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

internal object Prices {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private var nextRefresh = 0L
    private var pending = false
    var prices: Map<String, Double> = emptyMap()
        private set
    var names: Map<String, String> = emptyMap()
        private set
    var status = "Prices not loaded"
        private set
    private var npc: Map<String, Double> = emptyMap()
    fun parseMarket(products: com.google.gson.JsonObject): Map<String, Double> = products.entrySet().associate { (id, item) ->
        // Hypixel names this side sell_summary: it is the price for instantly selling into buy orders.
        val orders = item.asJsonObject.getAsJsonArray("sell_summary")
        id to (orders.firstOrNull()?.asJsonObject?.get("pricePerUnit")?.asDouble ?: 0.0)
    }.filterValues { it >= 0 && it.isFinite() }
    fun idForName(name: String): String? = names[name.lowercase()] ?: Catalog.aliases[name.lowercase()]
    fun displayName(id: String): String = GreenhouseMutation.fromInternalId(id)?.displayName ?: when (id) {
        "CARROT_ITEM" -> "Carrot"; "POTATO_ITEM" -> "Potato"; "NETHER_STALK" -> "Nether Wart"
        "INK_SACK:3" -> "Cocoa Beans"; "DOUBLE_PLANT" -> "Sunflower"; "MELON" -> "Melon Slice"
        else -> (names.entries.firstOrNull { it.value == id }?.key ?: id.replace('_',' ').lowercase())
            .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }
    fun id(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()
        val id = tag?.getString("id")?.orElse(null)
            ?: tag?.getCompound("ExtraAttributes")?.orElse(null)?.getString("id")?.orElse(null)
        return id?.takeIf { it.isNotEmpty() } ?: idForName(stack.cleanName)
    }
    fun price(id: String): Double? {
        Storage.settings.priceOverrides[id]?.let { return it }
        val direct = maxOf(prices[id] ?: 0.0, npc[id] ?: 0.0)
        val derived = Catalog.units.entries.filter { it.value.base == id }
            .map { (key, unit) -> maxOf(prices[key] ?: 0.0, npc[key] ?: 0.0) / unit.amount }.maxOrNull() ?: 0.0
        return maxOf(direct, derived).takeIf { it > 0 && it.isFinite() }
    }
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (pending || (!force && now < nextRefresh)) return
        pending = true; nextRefresh = now + 300_000
        CompletableFuture.supplyAsync {
            fun fetch(path: String): com.google.gson.JsonObject {
                val request = HttpRequest.newBuilder(URI("https://api.hypixel.net/v2/$path"))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", "Greenhouse/0.1.0").GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                require(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
                return JsonParser.parseString(response.body()).asJsonObject.also { require(it["success"].asBoolean) }
            }
            runCatching {
                val products = fetch("skyblock/bazaar").getAsJsonObject("products")
                val market = parseMarket(products)
                val items = if (names.isEmpty()) fetch("resources/skyblock/items").getAsJsonArray("items") else null
                val displayNames = items?.associate { it.asJsonObject["name"].asString.lowercase() to it.asJsonObject["id"].asString }
                val npcPrices = items?.mapNotNull {
                    val obj = it.asJsonObject
                    obj["npc_sell_price"]?.asDouble?.let { value -> obj["id"].asString to value }
                }?.toMap()
                Triple(market, displayNames, npcPrices)
            }
        }.thenAccept { result -> Minecraft.getInstance().execute {
            pending = false
            result.onSuccess { (market, displayNames, npcPrices) ->
                prices = market
                if (displayNames != null) names = displayNames
                if (npcPrices != null) npc = npcPrices
                status = "Bazaar / NPC prices"
            }.onFailure { status = if (prices.isEmpty()) "Prices unavailable" else "Cached prices (refresh failed)" }
        } }
    }
}

internal object Catalog {
    data class Unit(val base: String, val amount: Long)
    val units: Map<String, Unit> = buildMap {
        fun family(base: String, vararg forms: Pair<String, Long>) {
            put(base, Unit(base, 1)); forms.forEach { (id, amount) -> put(id, Unit(base, amount)) }
        }
        family("WHEAT", "HAY_BLOCK" to 9, "ENCHANTED_WHEAT" to 160, "ENCHANTED_HAY_BALE" to 25600)
        family("SEEDS", "ENCHANTED_SEEDS" to 160, "BOX_OF_SEEDS" to 25600)
        family("CARROT_ITEM", "ENCHANTED_CARROT" to 160, "ENCHANTED_GOLDEN_CARROT" to 25600)
        family("POTATO_ITEM", "ENCHANTED_POTATO" to 160, "ENCHANTED_BAKED_POTATO" to 25600)
        family("NETHER_STALK", "ENCHANTED_NETHER_STALK" to 160, "MUTANT_NETHER_STALK" to 25600)
        family("PUMPKIN", "ENCHANTED_PUMPKIN" to 160, "POLISHED_PUMPKIN" to 25600)
        family("MELON", "MELON_BLOCK" to 9, "ENCHANTED_MELON" to 160, "ENCHANTED_MELON_BLOCK" to 25600)
        family("INK_SACK:3", "ENCHANTED_COCOA" to 160, "ENCHANTED_COOKIE" to 25600)
        family("SUGAR_CANE", "ENCHANTED_SUGAR" to 160, "ENCHANTED_SUGAR_CANE" to 25600)
        family("CACTUS", "CACTUS_GREEN" to 1, "ENCHANTED_CACTUS_GREEN" to 160, "ENCHANTED_CACTUS" to 25600)
        family("RED_MUSHROOM", "HUGE_MUSHROOM_2" to 9, "ENCHANTED_RED_MUSHROOM" to 160, "ENCHANTED_HUGE_MUSHROOM_2" to 25600)
        family("BROWN_MUSHROOM", "HUGE_MUSHROOM_1" to 9, "ENCHANTED_BROWN_MUSHROOM" to 160, "ENCHANTED_HUGE_MUSHROOM_1" to 25600)
        family("DOUBLE_PLANT", "ENCHANTED_SUNFLOWER" to 160, "COMPACTED_SUNFLOWER" to 25600)
        family("MOONFLOWER", "ENCHANTED_MOONFLOWER" to 160, "COMPACTED_MOONFLOWER" to 25600)
        family("WILD_ROSE", "ENCHANTED_WILD_ROSE" to 160, "COMPACTED_WILD_ROSE" to 25600)
        listOf("CROPIE", "SQUASH", "FERMENTO", "HELIANTHUS", "SEASONING", "BURROWING_SPORES", "RAREFINDER_CHIP",
            "CORNUCOPIA", "CARROT_ZEST", "DEEPFRIES", "AGGOURDIAN", "CANE_KNOT", "MELON_JUICE", "CACTUS_FLOWER",
            "DESIGNER_COFFEE_BEANS", "FEASTFUNGUS", "BOTROOT", "SALTED_SUNFLOWER_SEEDS", "CRYSTALIZED_MOONLIGHT",
            "FLORAL_GELATIN", "WARTY", "ETHEREAL_VINE", "ALL_IN_ALOE_FRAGMENT").forEach { family(it) }
        GreenhouseMutation.entries.filter { it.internalId !in setOf("TIMESTALK", "ZOMBUD") }.forEach { family(it.internalId) }
    }
    val aliases = buildMap {
        units.keys.forEach { put(it.replace('_', ' ').lowercase(), it) }
        put("all-in aloe fragment", "ALL_IN_ALOE_FRAGMENT")
        put("carrot", "CARROT_ITEM"); put("potato", "POTATO_ITEM"); put("nether wart", "NETHER_STALK")
        put("melon slice", "MELON"); put("cocoa beans", "INK_SACK:3"); put("sunflower", "DOUBLE_PLANT")
        put("enchanted cocoa beans", "ENCHANTED_COCOA"); put("enchanted nether wart", "ENCHANTED_NETHER_STALK")
        GreenhouseMutation.entries.forEach { put(it.displayName.lowercase(), it.internalId) }
    }
    fun canonical(items: Map<String, Long>): Map<String, Long> = buildMap {
        items.forEach { (id, count) -> units[id]?.let { unit ->
            put(unit.base, getOrDefault(unit.base, 0) + count * unit.amount)
        } }
    }
}
