package com.fragpicker.android.core.ui.liquid.shapes

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.LayoutDirection

/** Backdrop 1.0 lens requires CornerBasedShape; retain the upstream continuous outline. */
internal class BackdropCapsule(
    topStart: CornerSize = CornerSize(50), topEnd: CornerSize = CornerSize(50),
    bottomEnd: CornerSize = CornerSize(50), bottomStart: CornerSize = CornerSize(50)
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
    override fun createOutline(size: Size, topStart: Float, topEnd: Float, bottomEnd: Float,
                               bottomStart: Float, layoutDirection: LayoutDirection): Outline =
        roundedRectangleOutline(size,
            if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd,
            if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart,
            if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart,
            if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd,
            RoundedCornerStyle.Continuous)

    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize): CornerBasedShape =
        BackdropCapsule(topStart, topEnd, bottomEnd, bottomStart)
}
