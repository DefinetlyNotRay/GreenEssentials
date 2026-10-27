package dev.greenhouse

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

internal object Prices {
    private const val REFRESH_INTERVAL_MILLIS = 300_000L
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private var nextRefreshAt = 0L
    private var refreshPending = false
    var prices: Map<String, Double> = emptyMap()
        private set

    var names: Map<String, String> = emptyMap()
        private set

    var status = "Prices not loaded"
        private set

    private var npcPrices: Map<String, Double> = emptyMap()

    fun parseMarket(products: JsonObject): Map<String, Double> = products.entrySet().associate { (id, item) ->
        // Hypixel names this side sell_summary: it is the price for instantly selling into buy orders.
        val orders = item.asJsonObject.getAsJsonArray("sell_summary")
        id to (orders.firstOrNull()?.asJsonObject?.get("pricePerUnit")?.asDouble ?: 0.0)
    }.filterValues { it >= 0 && it.isFinite() }

    fun idForName(name: String): String? = names[name.lowercase()] ?: Catalog.aliases[name.lowercase()]

    fun displayName(id: String): String {
        GreenhouseMutation.fromInternalId(id)?.let { return it.displayName }
        return when (id) {
            "CARROT_ITEM" -> "Carrot"
            "POTATO_ITEM" -> "Potato"
            "NETHER_STALK" -> "Nether Wart"
            "INK_SACK:3" -> "Cocoa Beans"
            "DOUBLE_PLANT" -> "Sunflower"
            "MELON" -> "Melon Slice"
            else -> {
                val name = names.entries.firstOrNull { it.value == id }?.key ?: id.replace('_', ' ').lowercase()
                name.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
            }
        }
    }

    fun id(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()
        val id = tag?.getString("id")?.orElse(null) ?: tag?.getCompound("ExtraAttributes")?.orElse(null)?.getString("id")?.orElse(null)
        return id?.takeIf { it.isNotEmpty() } ?: idForName(stack.cleanName)
    }

    fun price(id: String): Double? {
        Storage.settings.priceOverrides[id]?.let { return it }
        val direct = marketOrNpcPrice(id)
        val derived = Catalog.units.entries
            .filter { it.value.base == id }
            .map { (formId, unit) -> marketOrNpcPrice(formId) / unit.amount }
            .maxOrNull() ?: 0.0
        return maxOf(direct, derived).takeIf { it > 0 && it.isFinite() }
    }

    private fun marketOrNpcPrice(id: String): Double = maxOf(prices[id] ?: 0.0, npcPrices[id] ?: 0.0)

    private data class PriceUpdate(
        val market: Map<String, Double>,
        val displayNames: Map<String, String>?,
        val npcPrices: Map<String, Double>?,
    )

    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (refreshPending || (!force && now < nextRefreshAt)) return
        refreshPending = true
        nextRefreshAt = now + REFRESH_INTERVAL_MILLIS
        CompletableFuture.supplyAsync { runCatching { fetchPrices() } }
            .thenAccept { result ->
                Minecraft.getInstance().execute {
                    refreshPending = false
                    result.onSuccess(::applyPrices).onFailure {
                        status = if (prices.isEmpty()) "Prices unavailable" else "Cached prices (refresh failed)"
                    }
                }
            }
    }

    private fun fetchPrices(): PriceUpdate {
        val products = fetch("skyblock/bazaar").getAsJsonObject("products")
        val market = parseMarket(products)
        val items = if (names.isEmpty()) fetch("resources/skyblock/items").getAsJsonArray("items") else null
        val displayNames = items?.associate { item ->
            val data = item.asJsonObject
            data["name"].asString.lowercase() to data["id"].asString
        }
        val npcPrices = items?.mapNotNull { item ->
            val data = item.asJsonObject
            data["npc_sell_price"]?.asDouble?.let { value -> data["id"].asString to value }
        }?.toMap()
        return PriceUpdate(market, displayNames, npcPrices)
    }

    private fun fetch(path: String): JsonObject {
        val request = HttpRequest.newBuilder(URI("https://api.hypixel.net/v2/$path"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Greenhouse/0.1.0")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        return JsonParser.parseString(response.body()).asJsonObject.also { require(it["success"].asBoolean) }
    }

    private fun applyPrices(update: PriceUpdate) {
        prices = update.market
        update.displayNames?.let { names = it }
        update.npcPrices?.let { npcPrices = it }
        status = "Bazaar / NPC prices"
    }
}
