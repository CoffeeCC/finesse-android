package org.jellyfin.mobile.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.time.Duration

/** What to watch: Continue watching, then recently added films and episodes. */
class VideoListScreen(carContext: CarContext) : Screen(carContext), KoinComponent {
    private val api: ApiClient by inject()
    private val apiClientController: ApiClientController by inject()

    private sealed interface State {
        data object Loading : State
        data class Message(val text: String) : State
        data class Ready(val resume: List<BaseItemDto>, val recent: List<BaseItemDto>) : State
    }

    private var state: State = State.Loading

    init {
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        state = try {
            apiClientController.loadSavedServerUser()
            if (api.accessToken == null) {
                State.Message("Open Finesse on your phone and sign in.")
            } else {
                val types = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE)
                val resume by api.itemsApi.getItems(
                    includeItemTypes = types,
                    filters = listOf(ItemFilter.IS_RESUMABLE),
                    sortBy = listOf(ItemSortBy.DATE_PLAYED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    recursive = true,
                    limit = MAX_ITEMS,
                )
                val recent by api.itemsApi.getItems(
                    includeItemTypes = types,
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    recursive = true,
                    limit = MAX_ITEMS,
                )
                val resumeIds = resume.items.map { it.id }.toSet()
                State.Ready(resume.items, recent.items.filter { it.id !in resumeIds })
            }
        } catch (e: Exception) {
            Timber.e(e, "Couldn't load videos")
            State.Message("Couldn't reach your Finesse server. Check that the phone is online (and Tailscale is on, if you use it).")
        }
        invalidate()
    }

    override fun onGetTemplate(): Template {
        return when (val s = state) {
            State.Loading -> ListTemplate.Builder()
                .setTitle(TITLE)
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()

            is State.Message -> MessageTemplate.Builder(s.text)
                .setTitle(TITLE)
                .setHeaderAction(Action.APP_ICON)
                .addAction(
                    Action.Builder()
                        .setTitle("Try again")
                        .setOnClickListener {
                            state = State.Loading
                            invalidate()
                            lifecycleScope.launch { load() }
                        }
                        .build(),
                )
                .build()

            is State.Ready -> {
                // The car decides how many rows may show (fewer while driving).
                val limit = carContext.getCarService(ConstraintManager::class.java)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
                val builder = ListTemplate.Builder()
                    .setTitle(TITLE)
                    .setHeaderAction(Action.APP_ICON)
                var room = limit
                for ((title, items) in listOf("Continue watching" to s.resume, "Recently added" to s.recent)) {
                    val rows = items.take(room)
                    if (rows.isEmpty()) continue
                    room -= rows.size
                    val list = ItemList.Builder()
                    rows.forEach { list.addItem(row(it)) }
                    builder.addSectionedList(SectionedItemList.create(list.build(), title))
                }
                if (room == limit) {
                    builder.setSingleList(ItemList.Builder().setNoItemsMessage("Nothing to watch yet").build())
                }
                builder.build()
            }
        }
    }

    private fun row(item: BaseItemDto): Row {
        val title = when (item.type) {
            BaseItemKind.EPISODE -> listOfNotNull(item.seriesName, episodeCode(item)).joinToString(" · ")
            else -> item.name.orEmpty()
        }
        val detail = when {
            item.type == BaseItemKind.EPISODE -> item.name.orEmpty()
            else -> item.productionYear?.toString().orEmpty()
        }
        val resumeAt = item.userData?.playbackPositionTicks?.takeIf { it > 0 }?.ticks
        return Row.Builder()
            .setTitle(title.ifEmpty { "Untitled" })
            .apply {
                if (detail.isNotEmpty()) addText(detail)
                if (resumeAt != null) addText("Resume at ${clock(resumeAt)}")
            }
            .setOnClickListener { screenManager.push(VideoPlayerScreen(carContext, item)) }
            .build()
    }

    private fun episodeCode(item: BaseItemDto): String? {
        val season = item.parentIndexNumber ?: return null
        val episode = item.indexNumber ?: return null
        return "S$season:E$episode"
    }

    private companion object {
        const val TITLE = "Finesse Video"
        const val MAX_ITEMS = 20

        fun clock(d: Duration): String {
            val s = d.inWholeSeconds
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
