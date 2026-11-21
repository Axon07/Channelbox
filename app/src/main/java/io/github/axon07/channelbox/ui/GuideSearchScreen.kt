package io.github.axon07.channelbox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import kotlinx.coroutines.delay

/** What the search shows; kept outside the screen so it survives watching a result. */
@Stable
class GuideSearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<TvViewModel.SearchResults?>(null)
    var focusIndex = 0
}

/**
 * One search for everything: live channels, movies, TV shows and programmes in the TV guide.
 * OK on a channel watches it; on a movie plays it; on a show opens it; on a programme: watch (on
 * now), catch-up (earlier) or record (later).
 */
@Composable
fun GuideSearchScreen(
    state: GuideSearchState,
    vm: TvViewModel,
    now: Long,
    recordingKeys: Set<String>,
    timeFormat: (Long) -> String,
    onPick: (TvViewModel.GuideHit) -> Unit,
    onChannel: (io.github.axon07.channelbox.data.ChannelEntity) -> Unit,
    onMovie: (io.github.axon07.channelbox.data.VodEntity) -> Unit,
    onShow: (io.github.axon07.channelbox.data.VodEntity) -> Unit,
) {
    LaunchedEffect(state.query) {
        val q = state.query.trim()
        if (q.length < 2) {
            state.results = null
            return@LaunchedEffect
        }
        delay(250) // wait for typing to pause
        state.results = vm.searchEverything(q)
    }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { if (state.results?.isEmpty != false) field.requestFocus() else firstResult.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(start = 48.dp, end = 48.dp, top = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.Search, tint = Palette.Text, size = 26.dp)
            Spacer(Modifier.width(12.dp))
            Text("Search", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.widthIn(max = 640.dp)) {
            TvTextField("Channel, programme, movie or show", state.query, { state.query = it }, fieldModifier = Modifier.focusRequester(field))
        }
        Spacer(Modifier.height(16.dp))
        val results = state.results
        when {
            state.query.trim().length < 2 -> Hint("Type at least 2 letters. Searches live channels, Movies, TV Shows and the TV guide.")
            results == null -> Hint("Searching…")
            results.isEmpty -> Hint("Nothing matches “${state.query.trim()}”")
            else -> {
                // The first row of the first section gets the focus when the screen opens.
                val firstSection = when {
                    results.channels.isNotEmpty() -> 0
                    results.movies.isNotEmpty() -> 1
                    results.shows.isNotEmpty() -> 2
                    else -> 3
                }
                fun first(section: Int, index: Int): Modifier =
                    if (section == firstSection && index == 0) Modifier.focusRequester(firstResult) else Modifier
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    if (results.channels.isNotEmpty()) {
                        item(key = "h-ch") { SectionTitle("Channels", results.channels.size) }
                        results.channels.forEachIndexed { i, ch ->
                            val mod = first(0, i)
                            item(key = "ch-${ch.id}") {
                                ChannelResultRow(ch, mod) { state.focusIndex = i; onChannel(ch) }
                            }
                        }
                    }
                    if (results.movies.isNotEmpty()) {
                        item(key = "h-mo") { SectionTitle("Movies", results.movies.size) }
                        results.movies.forEachIndexed { i, m ->
                            val mod = first(1, i)
                            item(key = "mo-${m.id}") { VodResultRow(m, mod) { onMovie(m) } }
                        }
                    }
                    if (results.shows.isNotEmpty()) {
                        item(key = "h-sh") { SectionTitle("TV Shows", results.shows.size) }
                        results.shows.forEachIndexed { i, m ->
                            val mod = first(2, i)
                            item(key = "sh-${m.id}") { VodResultRow(m, mod) { onShow(m) } }
                        }
                    }
                    if (results.programmes.isNotEmpty()) {
                        item(key = "h-tv") { SectionTitle("On TV", results.programmes.size) }
                        results.programmes.forEachIndexed { i, hit ->
                            val mod = first(3, i)
                            item(key = "tv-$i-${hit.channel.id}-${hit.program.start}") {
                                ResultRow(
                                    hit = hit,
                                    now = now,
                                    recording = TvViewModel.recordingKey(hit.channel.id, hit.program.start) in recordingKeys,
                                    canCatchUp = vm.canCatchUp(hit.channel, hit.program),
                                    timeFormat = timeFormat,
                                    modifier = mod,
                                    onClick = {
                                        state.focusIndex = i
                                        onPick(hit)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun ResultSurface(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x0FFFFFFF),
            contentColor = Palette.Text,
            focusedContainerColor = Color(0x29FFFFFF),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = RoundedCornerShape(10.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) { content() }
}

@Composable
private fun ChannelResultRow(ch: io.github.axon07.channelbox.data.ChannelEntity, modifier: Modifier, onClick: () -> Unit) {
    ResultSurface(modifier, onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch.logoUrl, ch.name, Modifier.size(width = 56.dp, height = 34.dp))
            Spacer(Modifier.width(14.dp))
            Text(ch.number.toString(), fontSize = 14.sp, modifier = Modifier.widthIn(min = 44.dp))
            Text(ch.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(ch.groupTitle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp))
        }
    }
}

@Composable
private fun VodResultRow(item: io.github.axon07.channelbox.data.VodEntity, modifier: Modifier, onClick: () -> Unit) {
    ResultSurface(modifier, onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 34.dp, height = 50.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF151C25))) {
                if (!item.poster.isNullOrBlank()) {
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(item.poster).size(300, 450).build(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.year?.takeIf { it.isNotBlank() }, item.category.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 14.sp, color = Palette.TextDim)
}

@Composable
private fun ResultRow(
    hit: TvViewModel.GuideHit,
    now: Long,
    recording: Boolean,
    canCatchUp: Boolean,
    timeFormat: (Long) -> String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = hit.program
    val onNow = p.start <= now && now < p.stop
    val past = p.stop <= now
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x0FFFFFFF),
            contentColor = Palette.Text,
            focusedContainerColor = Color(0x29FFFFFF),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Accent), shape = RoundedCornerShape(8.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(hit.channel.logoUrl, hit.channel.name, Modifier.size(width = 64.dp, height = 40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${hit.channel.number}  ${hit.channel.name}  ·  ${dayLabel(p.start, now)} ${timeFormat(p.start)} – ${timeFormat(p.stop)}",
                    fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                p.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            val (label, color) = when {
                onNow -> "On now" to Palette.Accent
                past && canCatchUp -> "Catch-up" to Color(0x33FFFFFF)
                past -> "Finished" to Color(0x1AFFFFFF)
                recording -> "● Recording" to Palette.Recording.copy(alpha = 0.8f)
                else -> "Later" to Color(0x33FFFFFF)
            }
            Text(
                label, fontSize = 12.sp, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** "Today", "Tomorrow", "Yesterday" or the weekday. */
private fun dayLabel(time: Long, now: Long): String {
    fun dayOf(t: Long) = (t + java.util.TimeZone.getDefault().getOffset(t)) / 86_400_000L
    return when ((dayOf(time) - dayOf(now)).toInt()) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(time))
    }
}
