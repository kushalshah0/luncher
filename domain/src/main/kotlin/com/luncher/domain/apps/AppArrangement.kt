package com.luncher.domain.apps

/**
 * How the user arranged the apps, as stored: the order of the shown apps, and the hidden apps in
 * an order of their own. It may name apps that aren't installed right now, so that an app that
 * comes back keeps its place; [homeApps] matches it to the installed apps.
 */
data class AppArrangement(
    /** The shown apps in the user's order; null until the user first moves one, which means by label. */
    val order: List<LaunchableApp>?,
    /** The hidden apps, most recently hidden first unless the user moved them. */
    val hidden: List<LaunchableApp>,
    /**
     * The apps in the dock, in its order (NETTV fork); null until the user first changes it, which
     * means the first [DEFAULT_DOCK_SIZE] shown apps. Kept apart from shown and hidden: an app may
     * be in the dock and hidden from the tiles.
     */
    val docked: List<LaunchableApp>? = null,
) {
    companion object {
        /** Nothing arranged yet: every app shown, by label. */
        val NONE = AppArrangement(order = null, hidden = emptyList())

        /** How many apps the dock holds before the user first changes it: the first shown ones. */
        const val DEFAULT_DOCK_SIZE = 6
    }
}
