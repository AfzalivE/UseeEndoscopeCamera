package com.afzal.endoscope.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/** Expressive shapes, normalized to a 1×1 box. */
object EndoscopeShapes {
    val Circle: RoundedPolygon = RoundedPolygon.circle(numVertices = 12).normalized()

    val Cookie9: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 9,
        innerRadius = 0.82f,
        rounding = CornerRounding(radius = 0.3f),
        innerRounding = CornerRounding(radius = 0.3f),
    ).normalized()

    val Cookie12: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 12,
        innerRadius = 0.86f,
        rounding = CornerRounding(radius = 0.2f),
        innerRounding = CornerRounding(radius = 0.2f),
    ).normalized()

    val RoundedSquare: RoundedPolygon = RoundedPolygon(
        numVertices = 4,
        rounding = CornerRounding(radius = 0.35f),
    ).normalized()

    val Sunny: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 8,
        innerRadius = 0.78f,
        rounding = CornerRounding(radius = 0.15f),
    ).normalized()
}

/** A [Shape] that shows [morph] at [progress] (0 = start, 1 = end), rotated by [rotation] degrees. */
class MorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val rotation: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        val matrix = android.graphics.Matrix()
        matrix.setScale(size.width, size.height)
        matrix.postRotate(rotation, size.width / 2f, size.height / 2f)
        path.transform(matrix)
        return Outline.Generic(path.asComposePath())
    }
}

/** A static [Shape] for one polygon. */
class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath()
        val matrix = android.graphics.Matrix()
        matrix.setScale(size.width, size.height)
        path.transform(matrix)
        return Outline.Generic(path.asComposePath())
    }
}
