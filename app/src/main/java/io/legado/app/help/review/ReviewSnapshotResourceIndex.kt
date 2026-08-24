package io.legado.app.help.review

private val reviewResourceKeyPattern = Regex("[0-9a-f]{64}")

/**
 * Resolves snapshot resource references by content key. Multiple URL entries may share one key;
 * all entries for that key must agree on the content length and therefore the same blob.
 */
internal fun indexedReviewResources(
    resources: List<ReviewSnapshotResourceEntry>,
    keys: List<String>,
): Map<String, List<ReviewSnapshotResourceEntry>> {
    require(keys.distinct().size == keys.size) {
        "review snapshot contains duplicate resource keys"
    }
    keys.forEach { key ->
        require(reviewResourceKeyPattern.matches(key)) {
            "review snapshot contains invalid resource key: $key"
        }
    }
    val entriesByKey = resources.groupBy { it.key }
    return keys.associateWith { key ->
        val matches = entriesByKey[key].orEmpty()
        require(matches.isNotEmpty()) {
            "review snapshot resource is not indexed: $key"
        }
        require(matches.mapTo(hashSetOf()) { it.byteCount }.size == 1) {
            "review snapshot resource entries disagree on byteCount: $key"
        }
        matches
    }
}
