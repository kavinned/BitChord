package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs

/** A unique queue result, so repeating the same action restarts its lifetime. */
data class QueueActionNotice(
    val id: Int,
    val message: String,
)

/** Whether a horizontal drag is far enough to dismiss the notice. */
internal fun shouldDismissQueueActionNotice(dragPx: Float, widthPx: Float): Boolean =
    widthPx > 0f && abs(dragPx) >= widthPx * 0.35f

/** Brief queue confirmation shown above the mini player or over the full player. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun QueueActionNoticeHost(
    notice: QueueActionNotice?,
    hazeState: HazeState,
    onDismiss: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val dismissCurrent by rememberUpdatedState(onDismiss)
    val surface = MaterialTheme.colorScheme.surface
    val shape = RoundedCornerShape(5.dp)

    AnimatedContent(
        targetState = notice,
        transitionSpec = {
            if (reduceAnimation) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn() + slideInVertically { height -> height / 2 }) togetherWith
                    (fadeOut() + slideOutVertically { height -> height / 2 })
            }
        },
        contentKey = { it?.id },
        label = "queueActionNotice",
        modifier = modifier.fillMaxWidth(),
    ) { current ->
        if (current != null) {
            val dragOffset = remember(current.id) { mutableFloatStateOf(0f) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .graphicsLayer { translationX = dragOffset.floatValue }
                    .pointerInput(current.id) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragOffset.floatValue += amount
                                if (shouldDismissQueueActionNotice(dragOffset.floatValue, size.width.toFloat())) {
                                    dismissCurrent(current.id)
                                }
                            },
                            onDragEnd = { dragOffset.floatValue = 0f },
                            onDragCancel = { dragOffset.floatValue = 0f },
                        )
                    }
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        dismiss {
                            dismissCurrent(current.id)
                            true
                        }
                    }
                    .shadow(4.dp, shape)
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
                    .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape),
            ) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}
