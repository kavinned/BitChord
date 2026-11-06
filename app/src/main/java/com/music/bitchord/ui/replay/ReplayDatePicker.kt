package com.music.bitchord.ui.replay

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.ui.components.optimizedHazeEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private val WHEEL_ITEM_HEIGHT = 40.dp
private const val WHEEL_VISIBLE_ROWS = 5

internal fun replayMonthsInYear(year: Int, earliest: YearMonth, latest: YearMonth): List<YearMonth> =
    (1..12).map { YearMonth.of(year, it) }.filter { it >= earliest && it <= latest }

@OptIn(ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class)
@Composable
internal fun ReplayDatePicker(
    period: ReplayPeriod,
    selectedMonth: YearMonth,
    earliestMonth: YearMonth?,
    hazeState: HazeState,
    onSelect: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentMonth = YearMonth.now()
    val earliest = earliestMonth?.coerceAtMost(currentMonth) ?: currentMonth
    val initialMonth = selectedMonth.coerceIn(earliest, currentMonth)
    var draftMonth by remember(period, initialMonth, earliest, currentMonth) { mutableStateOf(initialMonth) }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val title = period.localizedDateLabel(LocalContext.current, draftMonth)
    val monthLabel = stringResource(R.string.replay_period_monthly)
    val yearLabel = stringResource(R.string.replay_period_yearly)
    val surface = MaterialTheme.colorScheme.surface
    val shape = RoundedCornerShape(24.dp)
    val years = remember(earliest, currentMonth) { (earliest.year..currentMonth.year).toList() }
    val months = remember(draftMonth.year, earliest, currentMonth) {
        replayMonthsInYear(draftMonth.year, earliest, currentMonth)
    }
    val monthNames = remember(months) {
        val formatter = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
        months.map { formatter.format(it.atDay(1)).replaceFirstChar { first -> first.titlecase(Locale.getDefault()) } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .shadow(20.dp, shape)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin(surface),
                        )
                    },
                )
                .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
                .clickable(onClick = {})
                .semantics { paneTitle = title },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (period == ReplayPeriod.THIS_MONTH) {
                    ReplayWheel(
                        items = monthNames,
                        selectedIndex = months.indexOf(draftMonth).coerceAtLeast(0),
                        label = monthLabel,
                        modifier = Modifier.weight(1f),
                        onSelectedIndex = { index -> months.getOrNull(index)?.let { draftMonth = it } },
                    )
                }
                val displayedYears = years.map(Int::toString)
                ReplayWheel(
                    items = displayedYears,
                    selectedIndex = years.indexOf(draftMonth.year).coerceAtLeast(0),
                    label = yearLabel,
                    modifier = Modifier.weight(1f),
                    onSelectedIndex = { index ->
                        years.getOrNull(index)?.let { year ->
                            val validMonths = replayMonthsInYear(year, earliest, currentMonth)
                            if (validMonths.isNotEmpty()) draftMonth = draftMonth.coerceIn(validMonths.first(), validMonths.last())
                        }
                    },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PickerAction(stringResource(R.string.cancel), onDismiss)
                PickerAction(stringResource(R.string.done)) { onSelect(draftMonth) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplayWheel(
    items: List<String>,
    selectedIndex: Int,
    label: String,
    modifier: Modifier = Modifier,
    onSelectedIndex: (Int) -> Unit,
) {
    val listItems = remember(items) { List(2) { null } + items + List(2) { null } }
    val listState = rememberLazyListState()
    val currentOnSelectedIndex by rememberUpdatedState(onSelectedIndex)

    LaunchedEffect(items, selectedIndex) {
        if (items.isNotEmpty()) listState.scrollToItem(selectedIndex.coerceIn(items.indices))
    }
    LaunchedEffect(listState, listItems) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index?.minus(2)
        }
            .filter { it != null && it in items.indices }
            .distinctUntilChanged()
            .collect { it?.let(currentOnSelectedIndex) }
    }

    Box(modifier.height(WHEEL_ITEM_HEIGHT * WHEEL_VISIBLE_ROWS)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(WHEEL_ITEM_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)),
        )
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = label
                    stateDescription = items.getOrNull(selectedIndex).orEmpty()
                },
        ) {
            itemsIndexed(listItems) { index, item ->
                Box(Modifier.fillMaxWidth().height(WHEEL_ITEM_HEIGHT), Alignment.Center) {
                    if (item != null) {
                        Text(
                            text = item,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (index - 2 == selectedIndex) 1f else 0.56f,
                            ),
                            style = if (index - 2 == selectedIndex) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    )
}
