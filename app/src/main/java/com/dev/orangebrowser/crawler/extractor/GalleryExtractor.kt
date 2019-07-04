package com.dev.orangebrowser.crawler.extractor

import com.dev.orangebrowser.crawler.model.*
import org.jsoup.nodes.Element

class GalleryExtractor(
    var imageMeta: ItemExtractorMeta?=null,
    var articleExtractor: ArticleExtractor
) : BaseExtractor() {
    fun extract(element: Element, oldGallery: Gallery) {
        imageMeta?.apply {
            val meta=this
            if (meta.selector.isNotBlank()){
                val images=element.select(this.selector).map {
                    val value=it.attr(meta.attribute)
                    if (meta.script.isNotBlank()){
                        this@GalleryExtractor.executeScript(value,meta.script)
                    }else{
                        value
                    }
                }
                oldGallery.images.addAll(images)
            }
        }
        articleExtractor.extract(element,oldGallery.article)
    }

    fun extractNextPageUrl(element: Element): String {
        return articleExtractor.extractNextPageUrl(element)
    }
    companion object {
        fun build(
            imageMeta: ItemExtractorMeta?=null,
            titleMeta: ItemExtractorMeta? = null,
            dateMeta: ItemExtractorMeta? = null,
            coverMeta: ItemExtractorMeta? = null,
            abstractMeta: ItemExtractorMeta? = null,
            scoreMeta: ItemExtractorMeta? = null,
            contentMeta: ItemExtractorMeta? = null,
            viewCountMeta: ItemExtractorMeta? = null,
            nextPageMeta: ItemExtractorMeta? = null,
            authorIdMeta: ItemExtractorMeta? = null,
            authorNameMeta: ItemExtractorMeta? = null,
            authorAvatarMeta: ItemExtractorMeta? = null
        ): GalleryExtractor {
            val articleExtractor= ArticleExtractor(
                titleMeta,
                dateMeta,
                coverMeta,
                abstractMeta,
                scoreMeta,
                contentMeta,
                viewCountMeta,
                nextPageMeta,
                authorIdMeta,
                authorNameMeta,
                authorAvatarMeta
            )
           return GalleryExtractor(imageMeta,articleExtractor)
        }
    }
}