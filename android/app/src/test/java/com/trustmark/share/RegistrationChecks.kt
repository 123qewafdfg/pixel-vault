package com.trustmark.share

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class RegistrationChecks {
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    @Test fun unorderedLinksAreBoundToContent() {
        val parts=listOf("encrypted-part-0","encrypted-part-1","encrypted-part-2")
        val matcher=ContentLinkMatcher<String>(parts.map(::hash))
        listOf(2,0,1).forEach{matcher.accept(hash(parts[it]),"https://example.com/$it")}
        assertTrue(matcher.complete)
        assertEquals(listOf("https://example.com/0","https://example.com/1","https://example.com/2"),parts.indices.map{matcher.matches.getValue(it)})
    }
    @Test fun repeatedContentAndUnrelatedImagesNeverFillMissingParts() {
        val matcher=ContentLinkMatcher<String>(listOf(hash("a"),hash("b")))
        assertEquals(ContentLinkMatcher.Outcome.MATCHED,matcher.accept(hash("b"),"first-link"))
        assertEquals(ContentLinkMatcher.Outcome.DUPLICATE,matcher.accept(hash("b"),"different-link-same-image"))
        assertEquals(ContentLinkMatcher.Outcome.UNRELATED,matcher.accept(hash("resized-b"),"modified-image"))
        assertFalse(matcher.complete)
        assertEquals("first-link",matcher.matches[1])
        assertEquals(1,matcher.matches.size)
    }
    @Test fun missingImagesCanBeAddedWithoutLosingVerifiedResults() {
        val expected=listOf(hash("a"),hash("b"))
        val matcher=ContentLinkMatcher(expected,mapOf(1 to (expected[1] to "saved-b")))
        matcher.accept(hash("a"),"new-a")
        assertTrue(matcher.complete)
        assertEquals("saved-b",matcher.matches[1])
    }
    @Test fun regeneratedImagesInvalidatePreviousVerification() {
        val matcher=ContentLinkMatcher(listOf(hash("new-a")),mapOf(0 to (hash("old-a") to "old-link")))
        assertFalse(matcher.complete)
        assertTrue(matcher.matches.isEmpty())
    }
    @Test fun duplicateLocalCarriersAreRejected() {
        assertThrows(IllegalArgumentException::class.java){ContentLinkMatcher<String>(listOf(hash("same"),hash("same")))}
    }
    @Test fun nuxtFunctionLiteralsPreserveUrlsAndSortImages() {
        val html="""<script>window.__NUXT__=(function(a,b,c){return{data:[{feedDetail:{images:[{picUrl:a,display_index:2},{picUrl:b,display_index:1},{picUrl:c,display_index:3}]}}]}}("https:\u002F\u002Fchannelr.photo.store.qq.com/one?o=&bo=*!","https://channelr.photo.store.qq.com/two","https://evil.example/image"));</script>"""
        assertEquals(listOf("https://channelr.photo.store.qq.com/two","https://channelr.photo.store.qq.com/one?o=&bo=*!"),QqChannelClient.parseHtml(html))
    }
    @Test fun nuxtFlattenedStateResolvesReferencesAndSortsByScalarIndex() {
        val html="""<script id="__NUXT_DATA__" type="application/json">[{"feedDetail":1},{"images":2},[3,4],{"picUrl":5,"display_index":6},{"picUrl":7,"display_index":8},"https://channelr.photo.store.qq.com/second",2,"https://channelr.photo.store.qq.com/first",1]</script>"""
        assertEquals(listOf("https://channelr.photo.store.qq.com/first","https://channelr.photo.store.qq.com/second"),QqChannelClient.parseHtml(html))
    }
    @Test fun unavailablePostsDoNotReturnCoverOrRandomPageImages() {
        assertTrue(QqChannelClient.parseHtml("<img src=\"https://channelr.photo.store.qq.com/cover\">").isEmpty())
        assertTrue(QqChannelClient.parseState("{\"data\":[{\"feedDetail\":{\"images\":[]}}]}").isEmpty())
    }
    @Test fun duplicateKeysKeepEscapedPathsAndNormalizeOnlyHostAndDefaultPort() {
        assertEquals("https://example.com/a%2Fb?x=%2F",RemoteRegistration.canonical("HTTPS://EXAMPLE.com:443/a%2Fb?x=%2F"))
        assertTrue(RemoteRegistration.duplicates(listOf("https://EXAMPLE.com:443/a","https://example.com/a")))
        assertFalse(RemoteRegistration.duplicates(listOf("https://example.com/a%2Fb","https://example.com/a/b")))
    }
    @Test fun platformDomainsCannotBeSpoofedByUrlText() {
        assertTrue(CloudDriveService.trustedHost(com.yunx.app.data.network.SharePlatform.QUARK,"pan.quark.cn"))
        assertFalse(CloudDriveService.trustedHost(com.yunx.app.data.network.SharePlatform.QUARK,"pan.quark.cn.evil.example"))
        assertFalse(CloudDriveService.trustedHost(com.yunx.app.data.network.SharePlatform.LANZOU,"ilanzou.com"))
        assertTrue(CloudDriveService.trustedHost(com.yunx.app.data.network.SharePlatform.LANZOU,"ww.lanzouj.com"))
    }
}
