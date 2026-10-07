# AndroidLiquidGlass components

`core/ui/liquid` adapts LiquidBottomTabs, LiquidBottomTab, DampedDragAnimation,
InteractiveHighlight and DragGestureInspector from Kyant0/AndroidLiquidGlass,
kmp revision `65ab177e90e5c1d8c62e70cf7755841982da65f6`.
Source: https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6

`core/ui/liquid/shapes` contains the Capsule and necessary outline helpers from
the official `io.github.kyant0:shapes:1.2.1` sources, published under Apache-2.0.
Source: https://github.com/Kyant0/Shapes

Copyright 2025 Kyant. Apache License 2.0 is included in
`app/src/main/assets/licenses/AndroidLiquidGlass.txt` and packaged in the APK.

FragPicker modifications: package names, Android runtime shader/monotonic clock,
Compose 1.9 tint-layer equivalent, application-selected theme, accessibility
state, stable selection callbacks across recomposition, and explicit clipping
of the interactive highlight. A CornerBasedShape adapter preserves the continuous
outline while supplying the radius interface required by Backdrop 1.0. Original geometry,
colors, dimensions, lens parameters and spring/drag behavior are retained.
