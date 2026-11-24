package com.dhatech.roku.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhatech.roku.airplay.discovery.AirPlayReceiver
import com.dhatech.roku.airplay.discovery.DiscoverySource
import com.dhatech.roku.airplay.discovery.PairingPath
import com.dhatech.roku.cast.R

/**
 * One receiver in the discovery list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongParameterList")
@Composable
fun ReceiverCard(
    receiver: AirPlayReceiver,
    isPaired: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onCastClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    showAvatar: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "receiverCardScale",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) 28.dp else 20.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "receiverCardCorner",
    )

    Card(
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag("receiver_card_${receiver.address.hostAddress}_${receiver.port}")
            .scale(pressScale)
            .clip(RoundedCornerShape(cornerRadius))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            if (showAvatar) {
                ReceiverAvatar(receiver = receiver, isPaired = isPaired || isSelected)
                Spacer(Modifier.size(14.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = receiver.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = receiver.subtitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isPaired) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.receiver_paired),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(6.dp))
            }

            if (isSelected && onCastClick != null) {
                FilledIconButton(
                    onClick = onCastClick,
                    enabled = enabled,
                    modifier = Modifier.testTag(
                        "cast_selected_button_${receiver.address.hostAddress}_${receiver.port}",
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cast,
                        contentDescription = stringResource(R.string.action_cast_to, receiver.name),
                    )
                }
            } else {
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun ReceiverAvatar(receiver: AirPlayReceiver, isPaired: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isPaired || receiver.isRoku) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = receiverIcon(receiver),
                contentDescription = null,
                tint = if (isPaired || receiver.isRoku) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun receiverIcon(receiver: AirPlayReceiver): ImageVector = when {
    receiver.isAudioOnly -> Icons.Outlined.Speaker
    receiver.model?.startsWith("Mac") == true -> Icons.Outlined.LaptopMac
    else -> Icons.Outlined.Tv
}

@Composable
private fun AirPlayReceiver.subtitle(): String {
    val base = when {
        isAudioOnly -> stringResource(R.string.receiver_audio_only)
        !supportsMirroring -> stringResource(R.string.receiver_no_mirroring)
        pairingPath == PairingPath.LEGACY -> stringResource(R.string.receiver_legacy_pairing)
        isRoku -> stringResource(R.string.receiver_roku)
        else -> displayModel
    }
    val sourceSuffix = when (discoverySource) {
        DiscoverySource.MANUAL_IP -> " · ${stringResource(R.string.receiver_source_manual)}"
        DiscoverySource.SAVED_PROBE -> " · ${stringResource(R.string.receiver_source_saved)}"
        else -> ""
    }
    return "$base · $endpointKey$sourceSuffix"
}
