package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Interactive 3x3 Android-style Unlock Pattern Grid supporting drag connection, automatic
 * collinear intermediate dot bridging (e.g. 0->2 bridges 1), haptic ticks, and error feedback.
 */
@Composable
fun PatternLockGrid(
  onPatternCompleted: (List<Int>) -> Unit,
  modifier: Modifier = Modifier,
  isError: Boolean = false,
  enabled: Boolean = true,
  helperText: String = "Draw pattern (connect at least 4 dots)",
) {
  val haptic = LocalHapticFeedback.current
  val selectedNodes = remember { mutableStateListOf<Int>() }
  var pointerOffset by remember { mutableStateOf<Offset?>(null) }

  val primaryColor = MaterialTheme.colorScheme.primary
  val errorColor = MaterialTheme.colorScheme.error
  val idleColor = MaterialTheme.colorScheme.outlineVariant
  val surfaceDotColor = MaterialTheme.colorScheme.surfaceContainerHigh

  val activeColor by animateColorAsState(
    targetValue = if (isError) errorColor else primaryColor,
    label = "pattern_active_color",
  )

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier = modifier,
  ) {
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      tonalElevation = 2.dp,
      modifier = Modifier
        .widthIn(max = 280.dp)
        .fillMaxWidth()
        .aspectRatio(1f),
    ) {
      BoxWithConstraints(
        modifier = Modifier
          .fillMaxSize()
          .padding(18.dp),
      ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val centers = remember(widthPx, heightPx) {
          val colStep = widthPx / 3f
          val rowStep = heightPx / 3f
          List(9) { idx ->
            val row = idx / 3
            val col = idx % 3
            Offset(
              x = colStep * col + colStep / 2f,
              y = rowStep * row + rowStep / 2f,
            )
          }
        }
        val hitRadius = remember(widthPx, heightPx) {
          (minOf(widthPx, heightPx) / 3f) * 0.38f
        }

        fun hitNode(pos: Offset): Int? {
          for (idx in centers.indices) {
            val c = centers[idx]
            if (hypot(pos.x - c.x, pos.y - c.y) <= hitRadius) {
              return idx
            }
          }
          return null
        }

        fun appendNodeWithBridge(nodeIdx: Int) {
          if (nodeIdx in selectedNodes) return
          val prev = selectedNodes.lastOrNull()
          if (prev != null) {
            val mid = collinearMiddleNode(prev, nodeIdx)
            if (mid != null && mid !in selectedNodes) {
              selectedNodes.add(mid)
            }
          }
          selectedNodes.add(nodeIdx)
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        Canvas(
          modifier = Modifier
            .fillMaxSize()
            .semantics {
              contentDescription = "3 by 3 unlock pattern grid"
            }
            .testTag("pattern_lock_grid")
            .pointerInput(enabled, centers, hitRadius) {
              if (!enabled) return@pointerInput
              detectDragGestures(
                onDragStart = { startOffset ->
                  selectedNodes.clear()
                  pointerOffset = startOffset
                  hitNode(startOffset)?.let { appendNodeWithBridge(it) }
                },
                onDrag = { change, _ ->
                  change.consume()
                  pointerOffset = change.position
                  hitNode(change.position)?.let { appendNodeWithBridge(it) }
                },
                onDragEnd = {
                  pointerOffset = null
                  if (selectedNodes.isNotEmpty()) {
                    val captured = selectedNodes.toList()
                    selectedNodes.clear()
                    onPatternCompleted(captured)
                  }
                },
                onDragCancel = {
                  pointerOffset = null
                  selectedNodes.clear()
                },
              )
            },
        ) {
          val lineStrokePx = 6.dp.toPx()
          val haloStrokePx = 2.5.dp.toPx()
          val baseRadiusPx = 9.dp.toPx()
          val activeDotRadiusPx = 12.dp.toPx()
          val haloRadiusPx = 23.dp.toPx()

          // Draw connecting path segments between selected nodes
          if (selectedNodes.size >= 2) {
            for (i in 0 until selectedNodes.size - 1) {
              val start = centers[selectedNodes[i]]
              val end = centers[selectedNodes[i + 1]]
              drawLine(
                color = activeColor.copy(alpha = 0.28f),
                start = start,
                end = end,
                strokeWidth = lineStrokePx * 2.2f,
                cap = StrokeCap.Round,
              )
              drawLine(
                color = activeColor,
                start = start,
                end = end,
                strokeWidth = lineStrokePx,
                cap = StrokeCap.Round,
              )
            }
          }

          // Trailing line to active finger position
          val lastIdx = selectedNodes.lastOrNull()
          val dragPos = pointerOffset
          if (lastIdx != null && dragPos != null) {
            drawLine(
              color = activeColor.copy(alpha = 0.65f),
              start = centers[lastIdx],
              end = dragPos,
              strokeWidth = lineStrokePx * 0.85f,
              cap = StrokeCap.Round,
            )
          }

          // Draw all 9 pattern dots
          for (idx in centers.indices) {
            val center = centers[idx]
            val isSelected = idx in selectedNodes
            if (isSelected) {
              drawCircle(
                color = activeColor.copy(alpha = 0.16f),
                radius = haloRadiusPx,
                center = center,
              )
              drawCircle(
                color = activeColor,
                radius = haloRadiusPx,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                  width = haloStrokePx,
                  join = StrokeJoin.Round,
                ),
              )
              drawCircle(
                color = activeColor,
                radius = activeDotRadiusPx,
                center = center,
              )
            } else {
              drawCircle(
                color = surfaceDotColor,
                radius = baseRadiusPx + 5.dp.toPx(),
                center = center,
              )
              drawCircle(
                color = idleColor,
                radius = baseRadiusPx,
                center = center,
              )
            }
          }
        }
      }
    }

    Row(
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (selectedNodes.isNotEmpty()) {
          "Connected ${selectedNodes.size} dot(s)"
        } else {
          helperText
        },
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * Returns the intermediate node index if the segment from [a] to [b] passes cleanly
 * through the center of a collinear dot on the 3x3 grid (standard Android pattern rule).
 */
internal fun collinearMiddleNode(a: Int, b: Int): Int? {
  if (a !in 0..8 || b !in 0..8 || a == b) return null
  val r1 = a / 3
  val c1 = a % 3
  val r2 = b / 3
  val c2 = b % 3
  if ((r1 + r2) % 2 == 0 && (c1 + c2) % 2 == 0) {
    val midRow = (r1 + r2) / 2
    val midCol = (c1 + c2) / 2
    val mid = midRow * 3 + midCol
    if (mid != a && mid != b) return mid
  }
  return null
}
