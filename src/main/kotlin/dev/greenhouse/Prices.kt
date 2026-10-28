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
    private var book = PriceBook()

    var names: Map<String, String> = emptyMap()
        private set

    var status = "Prices not loaded"
        private set

    fun parseMarket(products: JsonObject, source: PriceSource = PriceSource.BAZAAR_INSTANT_SELL): Map<String, Double> {
        require(source == PriceSource.BAZAAR_INSTANT_BUY || source == PriceSource.BAZAAR_INSTANT_SELL)
        // Hypixel calls the offers used by instant buy buy_summary, and the bids sell_summary.
        val side = if (source == PriceSource.BAZAAR_INSTANT_BUY) "buy_summary" else "sell_summary"
        return products.entrySet().associate { (id, item) ->
            val orders = item.asJsonObject.getAsJsonArray(side)
            id to (orders?.firstOrNull()?.asJsonObject?.get("pricePerUnit")?.asDouble ?: 0.0)
        }.filterValues { it >= 0 && it.isFinite() }
    }

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

    fun price(id: String): Double? = priceAtSource(id, Storage.settings.priceSource)

    fun priceAtSource(id: String, source: PriceSource): Double? = book.price(id, source, Storage.settings.priceOverrides)

    fun autoPrice(id: String): Double? = book.price(id, PriceSource.AUTO, Storage.settings.priceOverrides)

    private data class PriceUpdate(
        val instantBuy: Map<String, Double>,
        val instantSell: Map<String, Double>,
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
                        status = if (book.instantSell.isEmpty() && book.instantBuy.isEmpty()) "Prices unavailable" else "Cached prices (refresh failed)"
                    }
                }
            }
    }

    private fun fetchPrices(): PriceUpdate {
        val products = fetch("skyblock/bazaar").getAsJsonObject("products")
        val instantBuy = parseMarket(products, PriceSource.BAZAAR_INSTANT_BUY)
        val instantSell = parseMarket(products, PriceSource.BAZAAR_INSTANT_SELL)
        val items = if (names.isEmpty()) fetch("resources/skyblock/items").getAsJsonArray("items") else null
        val displayNames = items?.associate { item ->
            val data = item.asJsonObject
            data["name"].asString.lowercase() to data["id"].asString
        }
        val npcPrices = items?.mapNotNull { item ->
            val data = item.asJsonObject
            data["npc_sell_price"]?.asDouble?.let { value -> data["id"].asString to value }
        }?.toMap()
        return PriceUpdate(instantBuy, instantSell, displayNames, npcPrices)
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
        book = PriceBook(update.instantBuy, update.instantSell, update.npcPrices ?: book.npc)
        update.displayNames?.let { names = it }
        status = "Bazaar / NPC prices"
    }
}
