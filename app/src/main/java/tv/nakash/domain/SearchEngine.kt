package tv.nakash.domain

/** Normalize once when the local catalog changes, rather than once per key press. */
data class SearchDocument(val id:String,val title:String,val keywords:String="",val channelNumber:Int?=null) {
    val titleKey=SearchMatcher.key(title)
    val keywordsKey=SearchMatcher.key(keywords)
}
data class SearchMatches(val items:List<SearchDocument>,val total:Int,val suggestions:List<String>)
object SearchEngine {
    fun search(documents:List<SearchDocument>,query:String,limit:Int=60):SearchMatches {
        val q=SearchMatcher.key(query)
        if(q.isEmpty()) return SearchMatches(documents.take(limit),documents.size,emptyList())
        val terms=q.split(' ')
        val ranked=documents.mapNotNull { d ->
            val score=when {
                d.channelNumber!=null && q.toIntOrNull()==d.channelNumber -> 0
                d.titleKey==q -> 0
                d.titleKey.startsWith(q) -> 1
                d.titleKey.split(' ').any { it.startsWith(q) } -> 2
                d.titleKey.contains(q) -> 3
                terms.all { it in d.titleKey || it in d.keywordsKey } -> 4
                else -> return@mapNotNull null
            }
            score to d
        }.sortedBy {it.first}.map {it.second}
        return SearchMatches(ranked.take(limit),ranked.size,ranked.asSequence().filter { it.titleKey.contains(q) }.distinctBy {it.titleKey}.take(5).map {it.title}.toList())
    }
}
