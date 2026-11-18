package com.crsyyds.suzubot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crsyyds.suzubot.ui.theme.PclOk
import com.crsyyds.suzubot.ui.theme.PclTextFaint

/**
 * PCL 风格的基础组件。
 *
 * 全部是纯色 + 圆角，不用阴影、不用渐变、不用位图 —— 保住「轻量」。
 * 动画只用 animateFloatAsState / AnimatedVisibility，无额外依赖。
 */

val CardShape = RoundedCornerShape(14.dp)
val InnerShape = RoundedCornerShape(10.dp)
val ButtonShape = RoundedCornerShape(10.dp)

/** PCL 的主面板：深灰卡片 + 细描边。 */
@Composable
fun PclCard(
	modifier: Modifier = Modifier,
	accent: Color? = null,
	content: @Composable ColumnScope.() -> Unit
) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			.clip(CardShape)
			.background(MaterialTheme.colorScheme.surface)
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(
					start = if (accent != null) 19.dp else 16.dp,
					top = 16.dp,
					end = 16.dp,
					bottom = 16.dp
				),
			content = content
		)
		// 强调竖条：只有 3dp 宽，靠 align 贴左侧
		if (accent != null) {
			Box(
				modifier = Modifier
					.align(Alignment.TopStart)
					.fillMaxHeight()
					.width(3.dp)
					.background(accent)
			)
		}
	}
}

/** 区块标题（PCL 的粗体小节名 + 计数）。 */
@Composable
fun SectionTitle(
	text: String,
	modifier: Modifier = Modifier,
	trailing: (@Composable () -> Unit)? = null
) {
	Row(
		modifier = modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text,
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f)
		)
		trailing?.invoke()
	}
}

/** 键值一行：左侧灰标签，右侧值。 */
@Composable
fun KvRow(
	label: String,
	value: String,
	valueColor: Color = MaterialTheme.colorScheme.onSurface,
	mono: Boolean = false
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			label,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Spacer(Modifier.weight(1f))
		Text(
			value,
			style = if (mono) MaterialTheme.typography.bodyMedium.copy(
				fontFeatureSettings = "tnum"
			) else MaterialTheme.typography.bodyMedium,
			color = valueColor,
			fontWeight = FontWeight.Medium
		)
	}
}

/** PCL 的细分割线。 */
@Composable
fun PclDivider(modifier: Modifier = Modifier, alpha: Float = 1f) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(1.dp)
			.alpha(alpha)
			.background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
	)
}

/** 状态圆点 + 文案。 */
@Composable
fun StatusDot(color: Color, text: String) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(
			modifier = Modifier
				.size(7.dp)
				.clip(CircleShape)
				.background(color)
		)
		Spacer(Modifier.width(7.dp))
		Text(
			text,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

/** 可展开的信息行（点标题展开，带动画）。 */
@Composable
fun ExpandableSection(
	title: String,
	expanded: Boolean,
	onToggle: () -> Unit,
	content: @Composable ColumnScope.() -> Unit
) {
	val arrowAlpha by animateFloatAsState(
		targetValue = if (expanded) 1f else 0.55f,
		animationSpec = tween(180),
		label = "arrow"
	)
	Column(modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				// 先 padding 再 clickable：让整个行区域（含留白）都可点
				.padding(vertical = 4.dp)
				.clip(InnerShape)
				.clickable(onClick = onToggle)
				.padding(vertical = 10.dp, horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				title,
				style = MaterialTheme.typography.titleMedium,
				modifier = Modifier.weight(1f)
			)
			Text(
				if (expanded) "收起" else "展开",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier.alpha(arrowAlpha)
			)
		}
		AnimatedVisibility(
			visible = expanded,
			enter = fadeIn(tween(200)) + expandVertically(tween(220)),
			exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
		) {
			Column { content() }
		}
	}
}

/** 空状态占位。 */
@Composable
fun EmptyHint(text: String) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 18.dp),
		contentAlignment = Alignment.Center
	) {
		Text(
			text,
			style = MaterialTheme.typography.bodySmall,
			color = PclTextFaint
		)
	}
}

/** 小号标签（PCL 的灰底小胶囊）。 */
@Composable
fun PclChip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(6.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(horizontal = 7.dp, vertical = 2.dp)
	) {
		Text(text, style = MaterialTheme.typography.bodySmall, color = color)
	}
}

/** 健康色的辅助函数：给「在线/离线」这类状态选色。 */
fun healthColor(ok: Boolean, warn: Boolean = false): Color = when {
	ok -> PclOk
	warn -> Color(0xFFFFB020)
	else -> Color(0xFFFF5D5D)
}
