package com.afzal.endoscope.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.afzal.endoscope.EndoscopeController
import com.afzal.endoscope.PreviewView
import com.afzal.endoscope.R
import com.afzal.endoscope.ViewTransform
import java.util.Locale

@Composable
fun EndoscopeScreen(controller: EndoscopeController, onOpenMedia: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = controller.message
    LaunchedEffect(message) {
        if (message != null) snackbarHostState.showSnackbar(message.text)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Viewfinder(
                    controller,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                )
                CaptureControls(
                    controller = controller,
                    onOpenMedia = onOpenMedia,
                    vertical = true,
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Viewfinder(
                    controller,
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
                CaptureControls(
                    controller = controller,
                    onOpenMedia = onOpenMedia,
                    vertical = false,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                )
            }
        }
        SnackbarHost(
            snackbarHostState,
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        )
    }
}

@Composable
private fun Viewfinder(controller: EndoscopeController, modifier: Modifier) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(controller.shutterCount) {
        if (controller.shutterCount > 0) {
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(300))
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context -> PreviewView(context).also { controller.preview = it } },
            modifier = Modifier.fillMaxSize(),
        )

        // Pinch to zoom, double tap to reset the zoom.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(controller) {
                    detectTransformGestures { _, _, zoom, _ ->
                        controller.updateTransform(controller.transform.withZoom(controller.transform.zoom * zoom))
                    }
                }
                .pointerInput(controller) {
                    detectTapGestures(onDoubleTap = {
                        controller.updateTransform(controller.transform.withZoom(1f))
                    })
                },
        )

        if (flash.value > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
        }

        AnimatedVisibility(
            visible = !controller.hasImage,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            EmptyState(connected = controller.connected, message = controller.emptyMessage)
        }

        StatusChips(controller, Modifier.align(Alignment.TopStart).padding(16.dp))

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZoomPill(controller)
            ViewToolbar(controller)
        }
    }
}

@Composable
private fun EmptyState(connected: Boolean, message: String) {
    Column(
        Modifier.padding(32.dp).widthIn(max = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (connected) {
            ContainedLoadingIndicator(Modifier.size(96.dp))
        } else {
            val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing)),
                label = "rotation",
            )
            Box(
                Modifier
                    .size(112.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .clip(PolygonShape(EndoscopeShapes.Cookie12))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp).graphicsLayer { rotationZ = -rotation },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusChips(controller: EndoscopeController, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(controller.hasImage, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            val lensText = if (controller.lensCount > 1) {
                " · " + stringResource(R.string.lens_of, controller.lens, controller.lensCount)
            } else {
                ""
            }
            Chip(
                text = controller.resolution + " · " +
                    String.format(Locale.US, "%.0f fps", controller.fps) + lensText,
                container = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                content = MaterialTheme.colorScheme.onSurface,
            )
        }
        val seconds = controller.recordingSeconds
        AnimatedVisibility(seconds != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            val s = seconds ?: 0L
            val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "pulse",
            )
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .graphicsLayer { alpha = pulse }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.recording, s / 60, s % 60),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        val address = controller.streamAddress
        AnimatedVisibility(address != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            val client = controller.streamClient
            Chip(
                text = if (client != null) {
                    stringResource(R.string.stream_client, address ?: "", client)
                } else {
                    stringResource(R.string.stream_waiting, address ?: "")
                },
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = Icons.Rounded.Wifi,
            )
        }
    }
}

@Composable
private fun Chip(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    Surface(shape = CircleShape, color = container) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

@Composable
private fun ZoomPill(controller: EndoscopeController) {
    val zoom = controller.transform.zoom
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(0.85f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ZoomOut, contentDescription = null, modifier = Modifier.size(20.dp))
            Slider(
                value = zoom,
                onValueChange = { controller.updateTransform(controller.transform.withZoom(it)) },
                valueRange = 1f..ViewTransform.MAX_ZOOM,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Zoom" },
            )
            Icon(Icons.Rounded.ZoomIn, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                String.format(Locale.US, "%.1f×", zoom),
                style = MaterialTheme.typography.labelLargeEmphasized,
                modifier = Modifier.padding(start = 8.dp).width(40.dp),
            )
        }
    }
}

@Composable
private fun ViewToolbar(controller: EndoscopeController) {
    val t = controller.transform
    HorizontalFloatingToolbar(expanded = true) {
        IconButton(onClick = { controller.updateTransform(t.rotated()) }) {
            Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = stringResource(R.string.rotate))
        }
        FilledIconToggleButton(
            checked = t.mirror,
            onCheckedChange = { controller.updateTransform(t.copy(mirror = it)) },
        ) {
            Icon(Icons.Rounded.SwapHoriz, contentDescription = stringResource(R.string.mirror))
        }
        FilledIconToggleButton(
            checked = t.flip,
            onCheckedChange = { controller.updateTransform(t.copy(flip = it)) },
        ) {
            Icon(Icons.Rounded.Flip, contentDescription = stringResource(R.string.flip))
        }
        IconButton(onClick = { controller.switchLens() }, enabled = controller.lensCount > 1) {
            Icon(Icons.Rounded.Cameraswitch, contentDescription = stringResource(R.string.switch_lens))
        }
        FilledIconToggleButton(
            checked = controller.streamAddress != null,
            onCheckedChange = { controller.toggleStream() },
        ) {
            Icon(Icons.Rounded.Wifi, contentDescription = stringResource(R.string.stream))
        }
        IconButton(onClick = { controller.updateTransform(ViewTransform()) }, enabled = !t.isIdentity) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(R.string.reset))
        }
    }
}

@Composable
private fun CaptureControls(
    controller: EndoscopeController,
    onOpenMedia: () -> Unit,
    vertical: Boolean,
    modifier: Modifier,
) {
    val recording = controller.recordingSeconds != null
    val gallery: @Composable () -> Unit = {
        MorphButton(
            onClick = onOpenMedia,
            start = EndoscopeShapes.RoundedSquare,
            end = EndoscopeShapes.Circle,
            morphed = false,
            size = 56.dp,
            container = MaterialTheme.colorScheme.secondaryContainer,
            enabled = controller.lastMedia != null,
            contentDescription = stringResource(R.string.last),
        ) {
            Icon(Icons.Rounded.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    val shutter: @Composable () -> Unit = {
        MorphButton(
            onClick = { controller.takePhoto() },
            start = EndoscopeShapes.Circle,
            end = EndoscopeShapes.Cookie9,
            morphed = false,
            size = 88.dp,
            container = MaterialTheme.colorScheme.primary,
            enabled = controller.hasImage,
            contentDescription = stringResource(R.string.photo),
            rotation = 40f,
        ) {
            Icon(
                Icons.Rounded.PhotoCamera,
                null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
    val record: @Composable () -> Unit = {
        MorphButton(
            onClick = { controller.toggleRecording() },
            start = EndoscopeShapes.Circle,
            end = EndoscopeShapes.RoundedSquare,
            morphed = recording,
            size = 64.dp,
            container = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiaryContainer,
            enabled = controller.hasImage || recording,
            contentDescription = stringResource(if (recording) R.string.stop else R.string.record),
        ) {
            Icon(
                if (recording) Icons.Rounded.Stop else Icons.Rounded.Videocam,
                null,
                tint = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
    }

    if (vertical) {
        Column(
            modifier,
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            record()
            shutter()
            gallery()
        }
    } else {
        Row(
            modifier,
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            gallery()
            shutter()
            record()
        }
    }
}

/**
 * A button whose shape morphs from [start] to [end] while pressed or while [morphed] is true,
 * using the expressive spatial spring from the theme's motion scheme.
 */
@Composable
private fun MorphButton(
    onClick: () -> Unit,
    start: RoundedPolygon,
    end: RoundedPolygon,
    morphed: Boolean,
    size: Dp,
    container: Color,
    enabled: Boolean,
    contentDescription: String,
    rotation: Float = 0f,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val morph = remember(start, end) { Morph(start, end) }
    val progress by animateFloatAsState(
        targetValue = if (pressed || morphed) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "morph",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "scale",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MorphShape(morph, progress, rotation = progress * rotation),
        color = if (enabled) container else container.copy(alpha = 0.38f),
        interactionSource = interaction,
        modifier = Modifier
            .size(size)
            .scale(scale)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
