package se.pontus.glimt

/** Extra information om en film eller serie från leverantören. */
data class Details(
    val plot: String,
    val backdrop: String?,
    val rating: String?,
    val year: String?,
    val duration: String?,
    val genre: String?,
    /** För TheIntroDB (hoppa över intro/eftertexter). */
    val tmdbId: String? = null,
    val imdbId: String? = null,
)
