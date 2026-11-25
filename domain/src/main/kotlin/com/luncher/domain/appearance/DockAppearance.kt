package com.luncher.domain.appearance

/**
 * How the dock looks while the home screen is in [HomeMode.DOCK] (NETTV fork). Which apps it
 * holds is the arrangement's ([com.luncher.domain.apps.AppArrangement.docked]), not the look's.
 * [com.luncher.domain.layout.DockLayout] turns it into places and sizes.
 */
data class DockAppearance(
    /** An icon's diameter at rest, in dp. */
    val iconSizeDp: Int = 64,
    /** How much the focused icon grows, and its neighbors less. */
    val magnification: DockMagnification = DockMagnification.FULL,
    /** Whether the focused app's name shows above it, in a bubble. */
    val showName: Boolean = true,
    /** What the shelf under the icons is drawn as. */
    val shelf: DockShelf = DockShelf.GLASS,
    /** How round the shelf's ends are. */
    val shape: DockShape = DockShape.CURVED,
    /** Whether moving between the dock's icons plays a short, soft tick. */
    val sounds: Boolean = true,
)

/** How much the focused icon grows: [scale] times its size at rest. */
enum class DockMagnification(val scale: Float) {
    OFF(1f),
    SUBTLE(1.25f),
    FULL(1.5f),
}

/** The shelf under the dock's icons. */
enum class DockShelf {
    /** Frosted glass: the wallpaper behind it blurred, a light tint, a sheen and a rim. */
    GLASS,

    /** The same glass, tinted dark. */
    DARK,

    /** No shelf: the icons alone on the wallpaper. */
    NONE,
}

/**
 * The shelf's outline: always rounded, never a plain rectangle. [cornerRatio] is the corner's
 * radius as a part of the shelf's height.
 */
enum class DockShape(val cornerRatio: Float) {
    /** Continuous-curvature corners, as the dock of a Mac's: the curve runs into the edges. */
    CURVED(0.42f),

    /** Fully round ends. */
    PILL(0.5f),

    /** Smaller curved corners. */
    SOFT(0.24f),
}
